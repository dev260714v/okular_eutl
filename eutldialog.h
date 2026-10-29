/* SPDX-License-Identifier: GPL-2.0-or-later */
#pragma once
#include <QCryptographicHash>
#include <QDateTime>
#include <QDialog>
#include <QDialogButtonBox>
#include <QDir>
#include <QFile>
#include <QFileDialog>
#include <QFileInfo>
#include <QElapsedTimer>
#include <QLabel>
#include <QProgressBar>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMessageBox>
#include <QProcess>
#include <QPushButton>
#include <QSaveFile>
#include <QTemporaryDir>
#include <QTextBrowser>
#include <QTimer>
#include <QVBoxLayout>

namespace EutlUi {
struct Verdict { QString text; QString foreground; QString background; };
inline Verdict verdict(const QString &indication) {
    if (indication == QStringLiteral("TOTAL_PASSED"))
        return {QStringLiteral("✓ ÉRVÉNYES"), QStringLiteral("#14532d"), QStringLiteral("#dcfce7")};
    if (indication == QStringLiteral("TOTAL_FAILED"))
        return {QStringLiteral("✗ ÉRVÉNYTELEN"), QStringLiteral("#7f1d1d"), QStringLiteral("#fee2e2")};
    return {QStringLiteral("⚠ NEM ELLENŐRIZHETŐ"), QStringLiteral("#713f12"), QStringLiteral("#fef3c7")};
}
inline QString overallIndication(const QJsonArray &signatures, const QJsonObject &coverage = {}) {
    if (signatures.isEmpty()) return {};
    bool unknown = false;
    for (const auto &value : signatures) {
        const QString indication = value.toObject().value(QStringLiteral("indication")).toString();
        if (indication == QStringLiteral("TOTAL_FAILED")) return QStringLiteral("TOTAL_FAILED");
        if (indication != QStringLiteral("TOTAL_PASSED")) unknown = true;
    }
    return unknown || coverage.value(QStringLiteral("status")).toString() != QStringLiteral("FULL")
        ? QStringLiteral("INDETERMINATE") : QStringLiteral("TOTAL_PASSED");
}
inline QString badge(const QString &indication) {
    const auto v = verdict(indication);
    return QStringLiteral("<span style='color:%1; background-color:%2; font-weight:bold;'>%3</span>")
        .arg(v.foreground, v.background, v.text);
}
}

// No shell, HTTP server or external browser is used by the integration.
class EutlDialog final : public QDialog
{
public:
    EutlDialog(const QString &source, QWidget *parent, const QString &engine = QStringLiteral("/usr/bin/okular-eutl-engine")) : QDialog(parent)
    {
        setWindowTitle(QStringLiteral("EU-s elektronikus aláírások ellenőrzése"));
        resize(820, 640);
        auto *layout = new QVBoxLayout(this);
        m_heading = new QLabel(this);
        m_heading->setObjectName(QStringLiteral("eutl_status"));
        m_heading->setWordWrap(true);
        m_heading->setTextFormat(Qt::PlainText);
        m_heading->setMargin(14);
        layout->addWidget(m_heading);
        showVerdict(QStringLiteral("RUNNING"), QStringLiteral("Ellenőrzés folyamatban…"));
        m_progress = new QProgressBar(this);
        m_progress->setObjectName(QStringLiteral("eutl_progress"));
        m_progress->setRange(0, 0);
        m_progress->setTextVisible(false);
        m_progress->setAccessibleName(QStringLiteral("EU-s aláírás-ellenőrzés folyamatban"));
        layout->addWidget(m_progress);
        m_elapsedLabel = new QLabel(QStringLiteral("Eltelt idő: 0 mp"), this);
        layout->addWidget(m_elapsedLabel);
        m_clock.start();
        m_tick = new QTimer(this);
        connect(m_tick, &QTimer::timeout, this, [this] {
            m_elapsedLabel->setText(QStringLiteral("Ellenőrzés folyamatban · Eltelt idő: %1 mp").arg(m_clock.elapsed() / 1000));
        });
        m_tick->start(1000);
        m_view = new QTextBrowser(this);
        m_view->setOpenLinks(false);
        m_view->setOpenExternalLinks(false);
        layout->addWidget(m_view);
        m_details = new QTextBrowser(this);
        m_details->setObjectName(QStringLiteral("eutl_details"));
        m_details->setOpenLinks(false);
        m_details->setOpenExternalLinks(false);
        m_details->hide();
        layout->addWidget(m_details);
        auto *buttons = new QDialogButtonBox(QDialogButtonBox::Close, this);
        m_save = buttons->addButton(QStringLiteral("Jelentések mentése…"), QDialogButtonBox::ActionRole);
        m_save->setEnabled(false);
        m_detailToggle = buttons->addButton(QStringLiteral("Részletek megjelenítése"), QDialogButtonBox::ActionRole);
        m_detailToggle->setCheckable(true);
        m_detailToggle->setEnabled(false);
        connect(m_detailToggle, &QPushButton::toggled, this, [this](bool checked) {
            m_details->setVisible(checked);
            m_detailToggle->setText(checked ? QStringLiteral("Részletek elrejtése") : QStringLiteral("Részletek megjelenítése"));
        });
        m_close = buttons->button(QDialogButtonBox::Close);
        m_close->setText(QStringLiteral("Megszakítás"));
        layout->addWidget(buttons);
        connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
        connect(m_save, &QPushButton::clicked, this, [this] { saveReports(); });
        m_run.setAutoRemove(true);
        if (!m_run.isValid()) { fail(QStringLiteral("Nem hozható létre privát munkakönyvtár.")); return; }
        // Validate a byte-for-byte snapshot of the saved file, never a re-saved PDF.
        QFile input(source);
        QFile snapshot(m_run.filePath(QStringLiteral("input.pdf")));
        if (!input.open(QIODevice::ReadOnly) || input.size() > 256LL * 1024 * 1024
            || !snapshot.open(QIODevice::WriteOnly | QIODevice::NewOnly)) {
            fail(QStringLiteral("A mentett PDF nem olvasható, vagy nagyobb 256 MiB-nál.")); return;
        }
        QCryptographicHash hash(QCryptographicHash::Sha256);
        qint64 copied = 0;
        while (!input.atEnd()) {
            const QByteArray block = input.read(1024 * 1024);
            copied += block.size();
            if (block.isEmpty() || copied > 256LL * 1024 * 1024 || snapshot.write(block) != block.size()) {
                fail(QStringLiteral("Nem sikerült a PDF változatlan munkapéldányát elkészíteni.")); return;
            }
            hash.addData(block);
        }
        if (!snapshot.flush()) { fail(QStringLiteral("A munkapéldány írása sikertelen.")); return; }
        snapshot.close();
        m_hash = QString::fromLatin1(hash.result().toHex());
        m_source = source;
        m_view->setPlainText(QStringLiteral("Ellenőrzés folyamatban…\n\nA bizalmi listák letöltése és az aláírások vizsgálata akár egy percig is tarthat. Az ablak bezárásával megszakítható.\n\nA PDF a számítógépen marad.\n\nA vizsgált mentett fájl: ") + source);
        m_process = new QProcess(this);
        m_process->setStandardOutputFile(QProcess::nullDevice());
        m_process->setStandardErrorFile(m_run.filePath(QStringLiteral("engine.log")));
        m_timeout = new QTimer(this);
        m_timeout->setSingleShot(true);
        connect(m_timeout, &QTimer::timeout, this, [this] {
            m_done = true;
            m_process->kill();
            fail(QStringLiteral("Az ellenőrzés öt perc alatt nem fejeződött be. Nincs érvényességi eredmény."));
        });
        connect(m_process, &QProcess::errorOccurred, this, [this](QProcess::ProcessError error) {
            if (m_done || error != QProcess::FailedToStart) return;
            m_done = true;
            m_timeout->stop();
            fail(QStringLiteral("Nem indítható az /usr/bin/okular-eutl-engine. Telepítse az ellenőrzőmotort."));
        });
        connect(m_process, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this,
                [this](int code, QProcess::ExitStatus status) {
            if (m_done) return;
            m_done = true;
            m_timeout->stop();
            readResult(code, status);
        });
        m_process->setProgram(engine);
        m_process->setArguments({snapshot.fileName(), m_run.path(), QStringLiteral("/etc/okular-eutl")});
        QTimer::singleShot(0, this, [this] {
            m_process->start();
            m_timeout->start(300000);
        });
    }
    ~EutlDialog() override
    {
        if (m_process && m_process->state() != QProcess::NotRunning) {
            m_process->kill();
            m_process->waitForFinished(1000);
        }
    }
private:
    void showVerdict(const QString &indication, const QString &text = {})
    {
        const auto v = EutlUi::verdict(indication);
        m_heading->setText(text.isEmpty() ? v.text : text);
        if (indication == QStringLiteral("RUNNING")) {
            m_heading->setStyleSheet(QString());
        } else {
            m_heading->setStyleSheet(QStringLiteral("QLabel { color:%1; background-color:%2; border-radius:8px; font-size:18px; font-weight:bold; }").arg(v.foreground, v.background));
        }
    }
    void finishBusy()
    {
        m_tick->stop();
        m_progress->hide();
        m_close->setText(QStringLiteral("Bezárás"));
        m_elapsedLabel->setText(QStringLiteral("Eltelt idő: %1 mp").arg(m_clock.elapsed() / 1000));
    }
    void fail(const QString &reason)
    {
        finishBusy();
        showVerdict(QStringLiteral("INDETERMINATE"));
        QFile log(m_run.filePath(QStringLiteral("engine.log")));
        if (log.open(QIODevice::ReadOnly)) {
            if (log.size() > 65536) log.seek(log.size() - 65536);
            m_details->setPlainText(QString::fromUtf8(log.readAll()));
            m_detailToggle->setEnabled(true);
        }
        m_view->setPlainText(QStringLiteral("Az ellenőrzés nem fejeződött be.\n\n") + reason);
    }
    static QString escaped(const QString &s) { return s.toHtmlEscaped(); }
    void readResult(int code, QProcess::ExitStatus status)
    {
        QFile file(m_run.filePath(QStringLiteral("result.json")));
        if (status != QProcess::NormalExit || !file.open(QIODevice::ReadOnly) || file.size() > 4 * 1024 * 1024) {
            fail(QStringLiteral("Az ellenőrzőmotor nem adott feldolgozható eredményt.")); return;
        }
        QJsonParseError parseError;
        const QJsonDocument doc = QJsonDocument::fromJson(file.readAll(), &parseError);
        if (parseError.error != QJsonParseError::NoError || !doc.isObject()) {
            fail(QStringLiteral("Hibás eredményformátum.")); return;
        }
        const QJsonObject root = doc.object();
        if (root.value(QStringLiteral("schema")).toInt() != 1
            || root.value(QStringLiteral("sha256")).toString() != m_hash) {
            fail(QStringLiteral("Az eredmény nem a vizsgált PDF-példányhoz tartozik.")); return;
        }
        const QString state = root.value(QStringLiteral("status")).toString();
        if (state == QStringLiteral("ERROR")) {
            fail(root.value(QStringLiteral("error")).toString()); return;
        }
        if (code != 0 || (state != QStringLiteral("COMPLETED") && state != QStringLiteral("NO_SIGNATURES"))) {
            fail(QStringLiteral("Az ellenőrzőmotor sikertelenül állt le.")); return;
        }
        finishBusy();
        const QJsonArray signatures = root.value(QStringLiteral("signatures")).toArray();
        const auto coverage = root.value(QStringLiteral("documentCoverage")).toObject();
        const QString coverageStatus = coverage.value(QStringLiteral("status")).toString();
        showVerdict(EutlUi::overallIndication(signatures, coverage));
        QString html = QStringLiteral("<h2>EU-s aláírás-ellenőrzés</h2><p><b>Vizsgált mentett fájl:</b> %1<br><b>SHA-256:</b> %2<br><b>Ellenőrzés:</b> %3</p>")
            .arg(escaped(m_source), m_hash, escaped(root.value(QStringLiteral("validationTime")).toString()));
        if (state == QStringLiteral("NO_SIGNATURES")) {
            showVerdict(QStringLiteral("INDETERMINATE"), QStringLiteral("⚠ NINCS FELISMERT ALÁÍRÁS"));
            html += QStringLiteral("<p>A PDF-ben nem található a motor által felismert elektronikus aláírás.</p>");
        } else {
            if (signatures.isEmpty()) { fail(QStringLiteral("Hiányzó aláíráseredmények.")); return; }
            if (EutlUi::overallIndication(signatures, coverage) == QStringLiteral("TOTAL_PASSED"))
                html += QStringLiteral("<p><b>Minden felismert aláírás érvényes (%1 db).</b></p>").arg(signatures.size());
            else
                html += QStringLiteral("<p>%1 aláírás vizsgálata befejeződött. Az egyedi eredmények alább láthatók.</p>").arg(signatures.size());
            if (coverageStatus == QStringLiteral("FULL")) {
                html += QStringLiteral("<p><b>✓ A jelenlegi PDF-változatot legalább egy érvényes aláírás lefedi.</b> A többi aláírás korábbi változatra is vonatkozhat.</p>");
            } else {
                if (EutlUi::overallIndication(signatures, QJsonObject{{QStringLiteral("status"), QStringLiteral("FULL")}}) == QStringLiteral("TOTAL_PASSED"))
                    showVerdict(QStringLiteral("INDETERMINATE"), QStringLiteral("⚠ A JELENLEGI PDF LEFEDETTSÉGE NEM IGAZOLT"));
                html += coverageStatus == QStringLiteral("PARTIAL")
                    ? QStringLiteral("<p><b>⚠ Az aláírások korábbi PDF-változatokra vonatkoznak. A jelenlegi fájl további bájtokat vagy módosításokat tartalmaz; ezek elfogadhatóságát a részletes jelentés alapján kell értékelni.</b></p>")
                    : QStringLiteral("<p><b>⚠ Nem állapítható meg, hogy érvényes aláírás lefedi-e a jelenlegi PDF-változatot.</b></p>");
            }
            for (const QJsonValue &value : signatures) {
                const auto s = value.toObject();
                const QString indication = s.value(QStringLiteral("indication")).toString();
                const QString qualification = s.value(QStringLiteral("qualification")).toString();
                QString qualified = QStringLiteral("A DSS besorolása: ") + qualification;
                if (qualification == QStringLiteral("QESIG")) qualified = QStringLiteral("Minősített elektronikus aláírás (QESig)");
                else if (qualification == QStringLiteral("QESEAL")) qualified = QStringLiteral("Minősített elektronikus bélyegző (QESeal)");
                else if (qualification == QStringLiteral("ADESIG")) qualified = QStringLiteral("Fokozott biztonságú elektronikus aláírás (AdESig)");
                else if (qualification == QStringLiteral("ADESEAL")) qualified = QStringLiteral("Fokozott biztonságú elektronikus bélyegző (AdESeal)");
                html += QStringLiteral("<hr><h3>%1</h3><p>%2</p><p>%3</p>")
                    .arg(escaped(s.value(QStringLiteral("signedBy")).toString()), EutlUi::badge(indication), escaped(qualified));
                const QString signatureCoverage = s.value(QStringLiteral("coverage")).toString();
                html += signatureCoverage == QStringLiteral("FULL")
                    ? QStringLiteral("<p>Lefedettség: jelenlegi PDF-változat (a saját aláírásadat kivételével).</p>")
                    : signatureCoverage == QStringLiteral("PARTIAL")
                        ? QStringLiteral("<p>⚠ Lefedettség: korábbi PDF-változat.</p>")
                        : QStringLiteral("<p>⚠ Lefedettség: nem állapítható meg.</p>");
                const QString sub = s.value(QStringLiteral("subIndication")).toString();
                if (!sub.isEmpty()) html += QStringLiteral("<p><b>Indok:</b> %1</p>").arg(escaped(sub));
                if (!s.value(QStringLiteral("warnings")).toArray().isEmpty())
                    html += QStringLiteral("<p>⚠ Kiegészítő figyelmeztetés található a részletekben.</p>");
            }
        }
        html += QStringLiteral("<hr><p>A megállapítás a fenti lenyomatú mentett PDF-re és a DSS validálási szabályaira vonatkozik. Az aláírás minősítése külön adat az érvényességi eredménytől. Az aláírás által lefedett bájtokon kívüli változásokat a részletes jelentés alapján kell értékelni.</p><p>Ez a program nem minősített validálási szolgáltatás.</p>");
        m_view->setHtml(html);
        m_details->setPlainText(QString::fromUtf8(QJsonDocument(root).toJson(QJsonDocument::Indented)));
        m_detailToggle->setEnabled(true);
        m_save->setEnabled(true);
    }
    void saveReports()
    {
        const QString parent = QFileDialog::getExistingDirectory(this, QStringLiteral("Jelentések helye"));
        if (parent.isEmpty()) return;
        const QString name = QStringLiteral("okular-eutl-%1-%2").arg(QDateTime::currentDateTimeUtc().toString(QStringLiteral("yyyyMMdd-HHmmss-zzz")), m_hash.left(12));
        QDir dir(parent);
        if (!dir.mkdir(name) || !dir.cd(name)) {
            QMessageBox::warning(this, windowTitle(), QStringLiteral("Nem hozható létre új jelentéskönyvtár.")); return;
        }
        QStringList files = {QStringLiteral("result.json"), QStringLiteral("simple-report.xml"), QStringLiteral("detailed-report.xml"), QStringLiteral("diagnostic-data.xml"), QStringLiteral("etsi-report.xml"), QStringLiteral("engine.log")};
        for (const auto &name : files) {
            const QString path = m_run.filePath(name);
            if (QFile::exists(path) && !QFile::copy(path, dir.filePath(name))) {
                QMessageBox::warning(this, windowTitle(), QStringLiteral("A jelentések mentése csak részben sikerült.")); return;
            }
        }
        QMessageBox::information(this, windowTitle(), QStringLiteral("A jelentések elkészültek:\n") + dir.path());
    }
    QTemporaryDir m_run;
    QLabel *m_heading = nullptr;
    QProgressBar *m_progress = nullptr;
    QLabel *m_elapsedLabel = nullptr;
    QElapsedTimer m_clock;
    QTimer *m_tick = nullptr;
    QTextBrowser *m_details = nullptr;
    QPushButton *m_detailToggle = nullptr;
    QPushButton *m_close = nullptr;
    QTextBrowser *m_view = nullptr;
    QPushButton *m_save = nullptr;
    QProcess *m_process = nullptr;
    QTimer *m_timeout = nullptr;
    QString m_hash, m_source;
    bool m_done = false;
};
