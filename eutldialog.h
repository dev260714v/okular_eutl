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
#include <QThread>
#include <QPointer>
#include <QSet>
#include <QMap>
#include <memory>
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
    return unknown || (coverage.value(QStringLiteral("status")).toString() != QStringLiteral("FULL")
        && coverage.value(QStringLiteral("assessment")).toString() != QStringLiteral("TECHNICAL_ONLY"))
        ? QStringLiteral("INDETERMINATE") : QStringLiteral("TOTAL_PASSED");
}
inline QString badge(const QString &indication) {
    const auto v = verdict(indication);
    return QStringLiteral("<span style='color:%1; background-color:%2; font-weight:bold;'>%3</span>")
        .arg(v.foreground, v.background, v.text);
}
// Export only explicitly selected technical fields. Never copy free-text DSS
// messages, names, certificates, URLs, paths or exception text into this summary.
inline QJsonObject technicalSummary(const QJsonObject &root) {
    QJsonObject out{{QStringLiteral("summarySchema"), 1},
        {QStringLiteral("engine"), QStringLiteral("DSS 6.5.1 / Okular EUTL 0.1.4")}};
    const auto numbers = [](const QJsonObject &source, QJsonObject &target, const QStringList &keys) {
        for (const auto &key : keys) if (source.value(key).isDouble() || source.value(key).isBool()) target.insert(key, source.value(key));
    };
    const auto token = [](const QJsonValue &value) {
        const QString text = value.toString();
        for (const QChar c : text) if (!((c >= QLatin1Char('A') && c <= QLatin1Char('Z')) || c.isDigit() || c == QLatin1Char('_'))) return QString();
        return text.size() <= 128 ? text : QString();
    };
    const auto tokens = [&token](const QJsonObject &source, QJsonObject &target, const QStringList &keys) {
        for (const auto &key : keys) { const auto text = token(source.value(key)); if (!text.isEmpty()) target.insert(key, text); }
    };
    numbers(root, out, {QStringLiteral("schema"), QStringLiteral("fileSize"), QStringLiteral("elapsedMillis"), QStringLiteral("acceptedTrustedLists"),
        QStringLiteral("trustedCertificateCount"), QStringLiteral("listHintFallback")});
    tokens(root, out, {QStringLiteral("status"), QStringLiteral("timestampStatus"), QStringLiteral("errorCode")});
    const auto time = QDateTime::fromString(root.value(QStringLiteral("validationTime")).toString(), Qt::ISODateWithMs);
    if (time.isValid()) out.insert(QStringLiteral("validationTime"), time.toUTC().toString(Qt::ISODateWithMs));
    QJsonObject cache;
    const auto sourceCache = root.value(QStringLiteral("cache")).toObject();
    numbers(sourceCache, cache, {QStringLiteral("trustedListMaxAgeHours"), QStringLiteral("certificateMaxAgeDays"), QStringLiteral("storesValidationVerdicts"),
        QStringLiteral("maxTotalBytes"), QStringLiteral("maxEntries"), QStringLiteral("revocationMaxAgeSeconds"), QStringLiteral("trustedListLoadMillis"),
        QStringLiteral("trustedListCacheHits"), QStringLiteral("trustedListDownloadAttempts"), QStringLiteral("reusedSignerCertificates"),
        QStringLiteral("documentValidationMillis"), QStringLiteral("savedValidSignerCertificates"), QStringLiteral("totalResourceCacheHits"), QStringLiteral("totalResourceDownloadAttempts")});
    tokens(sourceCache, cache, {QStringLiteral("listScope")});
    out.insert(QStringLiteral("cache"), cache);
    const auto signatures = root.value(QStringLiteral("signatures")).toArray();
    const auto timestamps = root.value(QStringLiteral("timestamps")).toArray();
    QMap<QString, QString> aliases;
    for (int i = 0; i < signatures.size(); ++i) aliases.insert(signatures[i].toObject().value(QStringLiteral("id")).toString(), QStringLiteral("S%1").arg(i + 1));
    for (int i = 0; i < timestamps.size(); ++i) aliases.insert(timestamps[i].toObject().value(QStringLiteral("id")).toString(), QStringLiteral("T%1").arg(i + 1));
    const auto references = [&aliases](const QJsonArray &ids) {
        QJsonArray result;
        for (const auto &id : ids) if (aliases.contains(id.toString()) && !id.toString().isEmpty()) result.append(aliases.value(id.toString()));
        return result;
    };
    const auto inventory = [&](const QJsonArray &items, const QString &prefix) {
        QJsonArray result;
        for (int i = 0; i < items.size(); ++i) {
            const auto source = items[i].toObject();
            QJsonObject item{{QStringLiteral("id"), QStringLiteral("%1%2").arg(prefix).arg(i + 1)}};
            tokens(source, item, {QStringLiteral("indication"), QStringLiteral("subIndication"), QStringLiteral("qualification"), QStringLiteral("coverage"), QStringLiteral("type")});
            numbers(source, item, {QStringLiteral("signedRevisionEnd"), QStringLiteral("messageImprintFound"), QStringLiteral("messageImprintIntact"), QStringLiteral("coversSignature"), QStringLiteral("documentTimestamp")});
            for (const auto &key : {QStringLiteral("errors"), QStringLiteral("warnings"), QStringLiteral("qualificationErrors"), QStringLiteral("qualificationWarnings")}) {
                QJsonArray codes;
                for (const auto &message : source.value(key).toArray()) {
                    const auto code = token(message.toObject().value(QStringLiteral("key")));
                    if (!code.isEmpty()) codes.append(code);
                }
                item.insert(key, codes);
            }
            for (const auto &key : {QStringLiteral("timestampIds"), QStringLiteral("timestampedSignatureIds")})
                if (source.contains(key)) item.insert(key, references(source.value(key).toArray()));
            result.append(item);
        }
        return result;
    };
    out.insert(QStringLiteral("signatures"), inventory(signatures, QStringLiteral("S")));
    out.insert(QStringLiteral("timestamps"), inventory(timestamps, QStringLiteral("T")));
    out.insert(QStringLiteral("signatureCount"), signatures.size());
    out.insert(QStringLiteral("timestampCount"), timestamps.size());
    QJsonObject coverage;
    const auto sourceCoverage = root.value(QStringLiteral("documentCoverage")).toObject();
    tokens(sourceCoverage, coverage, {QStringLiteral("status"), QStringLiteral("assessment")});
    for (const auto &key : {QStringLiteral("coveringSignatureIds"), QStringLiteral("coveringTimestampIds"), QStringLiteral("earlierSignatureIds")})
        coverage.insert(key, references(sourceCoverage.value(key).toArray()));
    const auto sourceTail = sourceCoverage.value(QStringLiteral("technicalTail")).toObject();
    QJsonObject tail;
    tokens(sourceTail, tail, {QStringLiteral("policy"), QStringLiteral("kind"), QStringLiteral("byteCoverage"), QStringLiteral("contentAssessment")});
    numbers(sourceTail, tail, {QStringLiteral("unsignedBytes"), QStringLiteral("signedRevisionEnd")});
    if (!tail.isEmpty()) coverage.insert(QStringLiteral("technicalTail"), tail);
    out.insert(QStringLiteral("documentCoverage"), coverage);
    QJsonArray lists;
    for (const auto &value : root.value(QStringLiteral("trustedLists")).toArray()) {
        QJsonObject list{{QStringLiteral("id"), QStringLiteral("TL%1").arg(lists.size() + 1)}};
        const auto source = value.toObject();
        numbers(source, list, {QStringLiteral("usable")});
        tokens(source, list, {QStringLiteral("indication"), QStringLiteral("subIndication")});
        for (const auto &key : {QStringLiteral("issueDate"), QStringLiteral("nextUpdate")}) {
            const auto date = QDateTime::fromString(source.value(key).toString(), Qt::ISODateWithMs);
            if (date.isValid()) list.insert(key, date.toUTC().toString(Qt::ISODateWithMs));
        }
        lists.append(list);
    }
    out.insert(QStringLiteral("trustedLists"), lists);
    return out;
}
}

// No shell, HTTP server or external browser is used by the integration.
class EutlDialog final : public QDialog
{
public:
    EutlDialog(const QString &source, QWidget *parent, const QString &engine = QStringLiteral("/usr/bin/okular-eutl-engine")) : QDialog(parent)
    {
        setWindowTitle(QStringLiteral("EU-s elektronikus aláírások ellenőrzése"));
        resize(760, 480);
        auto *layout = new QVBoxLayout(this);
        m_heading = new QLabel(this);
        m_heading->setObjectName(QStringLiteral("eutl_status"));
        m_heading->setWordWrap(true);
        m_heading->setTextFormat(Qt::PlainText);
        m_heading->setMargin(14);
        layout->addWidget(m_heading);
        showVerdict(QStringLiteral("RUNNING"), QStringLiteral("Ellenőrzés folyamatban…"));
        m_timestampHeading = new QLabel(QStringLiteral("Időbélyegzés: ellenőrzés folyamatban…"), this);
        m_timestampHeading->setObjectName(QStringLiteral("eutl_timestamp_status"));
        m_timestampHeading->setWordWrap(true);
        m_timestampHeading->setMargin(10);
        layout->addWidget(m_timestampHeading);
        m_information = new QLabel(this);
        m_information->setObjectName(QStringLiteral("eutl_information"));
        m_information->setWordWrap(true);
        m_information->setTextFormat(Qt::PlainText);
        m_information->setMargin(10);
        m_information->setStyleSheet(QStringLiteral("QLabel { color:#1565c0; background-color:#e3f2fd; border-radius:8px; }"));
        m_information->hide();
        layout->addWidget(m_information);
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
        m_run->setAutoRemove(true);
        if (!m_run->isValid()) { fail(QStringLiteral("Nem hozható létre privát munkakönyvtár.")); return; }
        m_source = source;
        m_view->setObjectName(QStringLiteral("eutl_summary"));
        m_view->setPlainText(QStringLiteral("A mentett PDF előkészítése…"));
        m_process = new QProcess(this);
        m_process->setStandardOutputFile(QProcess::nullDevice());
        m_process->setStandardErrorFile(m_run->filePath(QStringLiteral("engine.log")));
        m_timeout = new QTimer(this);
        m_timeout->setSingleShot(true);
        connect(m_timeout, &QTimer::timeout, this, [this] {
            m_done = true;
            m_failureCode = QStringLiteral("TIMEOUT");
            m_process->kill();
            m_process->waitForFinished(1000);
            fail(QStringLiteral("Az ellenőrzés túllépte az időkorlátot. Nincs érvényességi eredmény."));
        });
        connect(m_process, &QProcess::errorOccurred, this, [this](QProcess::ProcessError error) {
            if (m_done || error != QProcess::FailedToStart) return;
            m_done = true;
            m_failureCode = QStringLiteral("ENGINE_START_FAILED");
            m_timeout->stop();
            fail(QStringLiteral("Nem indítható az /usr/bin/okular-eutl-engine. Telepítse az ellenőrzőmotort."));
        });
        connect(m_process, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this,
                [this](int code, QProcess::ExitStatus status) {
            m_exitCode = code;
            m_crashed = status != QProcess::NormalExit;
            if (m_done) return;
            m_done = true;
            m_timeout->stop();
            readResult(code, status);
        });
        m_process->setProgram(engine);
        // Copy/hash in a worker: large files must not block the dialog or cancellation.
        // The worker owns the temporary directory until it finishes, even after closing.
        auto state = std::make_shared<SnapshotState>();
        auto run = m_run;
        m_snapshot = QThread::create([source, run, state] {
            QFile input(source);
            QFile snapshot(run->filePath(QStringLiteral("input.pdf")));
            if (!input.open(QIODevice::ReadOnly) || input.size() > MaxInputBytes
                || !snapshot.open(QIODevice::WriteOnly | QIODevice::NewOnly)) {
                state->error = QStringLiteral("A PDF nem olvasható, vagy meghaladja a 2 GiB - 1 bájtos határt."); return;
            }
            QCryptographicHash hash(QCryptographicHash::Sha256);
            while (!input.atEnd()) {
                if (QThread::currentThread()->isInterruptionRequested()) return;
                const QByteArray block = input.read(1024 * 1024);
                state->size += block.size();
                if (block.isEmpty() || state->size > MaxInputBytes || snapshot.write(block) != block.size()) {
                    state->error = QStringLiteral("A PDF munkapéldányának elkészítése sikertelen (legfeljebb 2 GiB - 1 bájt)."); return;
                }
                hash.addData(block);
            }
            if (!snapshot.flush()) { state->error = QStringLiteral("A munkapéldány írása sikertelen."); return; }
            state->hash = QString::fromLatin1(hash.result().toHex());
        });
        connect(m_snapshot, &QThread::finished, this, [this, state] {
            if (!state->error.isEmpty()) { fail(state->error); return; }
            m_hash = state->hash;
            m_inputSize = state->size;
            m_view->setPlainText(QStringLiteral("Az aláírások és időbélyegek ellenőrzése folyamatban…"));
            m_process->setArguments({m_run->filePath(QStringLiteral("input.pdf")), m_run->path(), QStringLiteral("/etc/okular-eutl")});
            m_process->start();
            m_timeout->start(state->size > 256LL * 1024 * 1024 ? 1800000 : 300000);
        });
        connect(m_snapshot, &QThread::finished, m_snapshot, &QObject::deleteLater);
        m_snapshot->start();
    }

    ~EutlDialog() override
    {
        if (m_snapshot) m_snapshot->requestInterruption();
        if (m_process && m_process->state() != QProcess::NotRunning) {
            m_process->kill();
            m_process->waitForFinished(1000);
        }
    }
private:
    friend struct EutlDialogTest;
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
        m_finishedMillis = m_clock.elapsed();
        m_tick->stop();
        m_progress->hide();
        m_close->setText(QStringLiteral("Bezárás"));
        m_elapsedLabel->setText(QStringLiteral("Eltelt idő: %1 mp").arg(m_clock.elapsed() / 1000));
    }
    void fail(const QString &reason)
    {
        finishBusy();
        m_runStatus = QStringLiteral("ERROR");
        m_save->setEnabled(m_run->isValid());
        if (m_failureCode.isEmpty()) m_failureCode = QStringLiteral("ENGINE_RESULT_ERROR");
        showVerdict(QStringLiteral("INDETERMINATE"));
        m_timestampHeading->setText(QStringLiteral("Időbélyegzés: nem ellenőrizhető"));
        QFile log(m_run->filePath(QStringLiteral("engine.log")));
        if (log.open(QIODevice::ReadOnly)) {
            if (log.size() > 65536) log.seek(log.size() - 65536);
            m_details->setPlainText(QString::fromUtf8(log.readAll()));
            m_detailToggle->setEnabled(true);
        }
        m_view->setPlainText(QStringLiteral("Az ellenőrzés nem fejeződött be.\n\n") + reason);
    }
    static QString escaped(const QString &s) { return s.toHtmlEscaped(); }
    static QString localTime(const QString &value)
    {
        auto date = QDateTime::fromString(value, Qt::ISODateWithMs);
        return date.isValid() ? date.toLocalTime().toString(QStringLiteral("yyyy.MM.dd. HH:mm:ss t")) : value;
    }
    static QString timestampHtml(const QJsonObject &timestamp)
    {
        const QString indication = timestamp.value(QStringLiteral("indication")).toString();
        const bool valid = (indication == QStringLiteral("PASSED") || indication == QStringLiteral("TOTAL_PASSED"))
            && timestamp.value(QStringLiteral("messageImprintFound")).toBool() && timestamp.value(QStringLiteral("messageImprintIntact")).toBool();
        const bool failed = indication == QStringLiteral("FAILED") || indication == QStringLiteral("TOTAL_FAILED");
        const QString status = EutlUi::badge(valid ? QStringLiteral("TOTAL_PASSED") : failed ? QStringLiteral("TOTAL_FAILED") : QStringLiteral("INDETERMINATE"));
        const QString qualification = timestamp.value(QStringLiteral("qualification")).toString();
        const QString level = qualification == QStringLiteral("QTSA") ? QStringLiteral("minősített")
            : qualification == QStringLiteral("TSA") ? QStringLiteral("nem minősített") : QStringLiteral("minősítése nem állapítható meg");
        const QString type = timestamp.value(QStringLiteral("documentTimestamp")).toBool() ? QStringLiteral("Dokumentum-/archiválási időbélyeg")
            : timestamp.value(QStringLiteral("coversSignature")).toBool() ? QStringLiteral("Aláírást védő időbélyeg") : QStringLiteral("Adatot védő időbélyeg");
        return QStringLiteral("<p><b>%1: %2</b> (%3)<br>%4<br>%5</p>")
            .arg(escaped(type), status, escaped(level), escaped(localTime(timestamp.value(QStringLiteral("productionTime")).toString())), escaped(timestamp.value(QStringLiteral("producedBy")).toString()));
    }
    void readResult(int code, QProcess::ExitStatus status)
    {
        QFile file(m_run->filePath(QStringLiteral("result.json")));
        if (status != QProcess::NormalExit || !file.open(QIODevice::ReadOnly) || file.size() > 4 * 1024 * 1024) {
            fail(QStringLiteral("Az ellenőrzőmotor nem adott feldolgozható eredményt.")); return;
        }
        QJsonParseError parseError;
        const QJsonDocument doc = QJsonDocument::fromJson(file.readAll(), &parseError);
        if (parseError.error != QJsonParseError::NoError || !doc.isObject()) {
            fail(QStringLiteral("Hibás eredményformátum.")); return;
        }
        const QJsonObject root = doc.object();
        if (root.value(QStringLiteral("schema")).toInt() != 2
            || root.value(QStringLiteral("sha256")).toString() != m_hash) {
            fail(QStringLiteral("Az eredmény nem a vizsgált PDF-példányhoz tartozik.")); return;
        }
        m_result = root; // Only retain after schema/hash binding succeeds.
        const QString state = root.value(QStringLiteral("status")).toString();
        if (state == QStringLiteral("ERROR")) {
            m_failureCode = root.value(QStringLiteral("errorCode")).toString();
            fail(root.value(QStringLiteral("error")).toString()); return;
        }
        if (code != 0 || (state != QStringLiteral("COMPLETED") && state != QStringLiteral("NO_SIGNATURES"))) {
            fail(QStringLiteral("Az ellenőrzőmotor sikertelenül állt le.")); return;
        }
        if (!root.value(QStringLiteral("timestamps")).isArray()) {
            fail(QStringLiteral("Hiányzó időbélyegadatok. Frissítse az ellenőrzőmotort.")); return;
        }
        finishBusy();
        m_runStatus = state;
        const QJsonArray signatures = root.value(QStringLiteral("signatures")).toArray();
        const QJsonArray timestamps = root.value(QStringLiteral("timestamps")).toArray();
        const auto coverage = root.value(QStringLiteral("documentCoverage")).toObject();
        const QString coverageStatus = coverage.value(QStringLiteral("status")).toString();
        if (state == QStringLiteral("COMPLETED") && signatures.isEmpty() && timestamps.isEmpty()) {
            fail(QStringLiteral("Hiányzó aláírás- és időbélyegeredmények.")); return;
        }
        showVerdict(EutlUi::overallIndication(signatures, coverage));
        if (signatures.isEmpty()) showVerdict(QStringLiteral("INDETERMINATE"), QStringLiteral("⚠ NINCS FELISMERT ALÁÍRÁS"));
        else if (EutlUi::overallIndication(signatures, QJsonObject{{QStringLiteral("status"), QStringLiteral("FULL")}}) == QStringLiteral("TOTAL_PASSED")
                 && coverageStatus != QStringLiteral("FULL")
                 && coverage.value(QStringLiteral("assessment")).toString() != QStringLiteral("TECHNICAL_ONLY"))
            showVerdict(QStringLiteral("INDETERMINATE"), QStringLiteral("⚠ A JELENLEGI PDF LEFEDETTSÉGE NEM IGAZOLT"));
        bool failedTimestamp = false, unknownTimestamp = false;
        for (const auto &value : timestamps) {
            const auto timestamp = value.toObject();
            const auto indication = timestamp.value(QStringLiteral("indication")).toString();
            failedTimestamp |= indication == QStringLiteral("FAILED") || indication == QStringLiteral("TOTAL_FAILED");
            unknownTimestamp |= !((indication == QStringLiteral("PASSED") || indication == QStringLiteral("TOTAL_PASSED"))
                && timestamp.value(QStringLiteral("messageImprintFound")).toBool() && timestamp.value(QStringLiteral("messageImprintIntact")).toBool());
        }
        const QString timestampIndication = timestamps.isEmpty() || unknownTimestamp ? QStringLiteral("INDETERMINATE") : QStringLiteral("TOTAL_PASSED");
        const auto timestampVerdict = EutlUi::verdict(failedTimestamp ? QStringLiteral("TOTAL_FAILED") : timestampIndication);
        m_timestampHeading->setText(QStringLiteral("Időbélyegzés: ") + (timestamps.isEmpty() ? QStringLiteral("⚠ NINCS SZOLGÁLTATÓI IDŐBÉLYEG") : timestampVerdict.text));
        m_timestampHeading->setStyleSheet(QStringLiteral("QLabel { color:%1; background-color:%2; border-radius:8px; font-weight:bold; }")
            .arg(timestampVerdict.foreground, timestampVerdict.background));
        if (coverage.value(QStringLiteral("assessment")).toString() == QStringLiteral("TIMESTAMP_ONLY")) {
            m_information->setText(QStringLiteral("ⓘ A jelenlegi PDF-változatot utólagos dokumentum-időbélyeg védi. Az eredeti aláírás korábbi változatra vonatkozik; az időbélyeg az azóta történt módosítások aláíró általi jóváhagyását nem igazolja."));
            m_information->show();
        }
        QString html;
        QSet<QString> shownTimestamps;
        for (const auto &value : signatures) {
            const auto signature = value.toObject();
            html += QStringLiteral("<h3>%1</h3><p>Aláírás: %2</p>")
                .arg(escaped(signature.value(QStringLiteral("signedBy")).toString()), EutlUi::badge(signature.value(QStringLiteral("indication")).toString()));
            const QString qualification = signature.value(QStringLiteral("qualification")).toString();
            html += QStringLiteral("<p>%1</p>").arg(escaped(qualification == QStringLiteral("QESIG") ? QStringLiteral("Minősített elektronikus aláírás")
                : qualification == QStringLiteral("QESEAL") ? QStringLiteral("Minősített elektronikus bélyegző")
                : qualification == QStringLiteral("ADESIG") ? QStringLiteral("Fokozott biztonságú elektronikus aláírás")
                : qualification == QStringLiteral("ADESEAL") ? QStringLiteral("Fokozott biztonságú elektronikus bélyegző") : qualification));
            const QString claimed = signature.value(QStringLiteral("claimedSigningTime")).toString();
            if (!claimed.isEmpty()) html += QStringLiteral("<p>Aláíró által megadott idő: %1<br><small>Önmagában nem független időigazolás.</small></p>").arg(escaped(localTime(claimed)));
            const auto ids = signature.value(QStringLiteral("timestampIds")).toArray();
            bool hasProof = false;
            for (const auto &t : timestamps) {
                const auto timestamp = t.toObject();
                if (ids.contains(timestamp.value(QStringLiteral("id"))) && timestamp.value(QStringLiteral("coversSignature")).toBool()) {
                    hasProof = true; html += timestampHtml(timestamp);
                    shownTimestamps.insert(timestamp.value(QStringLiteral("id")).toString());
                }
            }
            if (!hasProof) html += QStringLiteral("<p>⚠ Az aláíráshoz nem található szolgáltatói időbélyeg, amely az aláírás létezését igazolja.</p>");
        }
        if (signatures.isEmpty()) html += QStringLiteral("<p>Elektronikus aláírás nem található.</p>");
        // Inventory also shows document, archival, content and unassociated timestamps.
        if (timestamps.isEmpty() && signatures.isEmpty()) html += QStringLiteral("<p>Időbélyegzés: nincs szolgáltatói időbélyeg.</p>");
        if (!timestamps.isEmpty()) {
            for (const auto &value : timestamps) {
                const auto timestamp = value.toObject();
                if (!shownTimestamps.contains(timestamp.value(QStringLiteral("id")).toString())) html += timestampHtml(timestamp);
            }
        }
        if (coverageStatus == QStringLiteral("FULL")) {
            const bool timestampCoverage = !coverage.value(QStringLiteral("coveringTimestampIds")).toArray().isEmpty();
            html += timestampCoverage ? QStringLiteral("<p>✓ A jelenlegi PDF-változatot érvényes dokumentum-időbélyeg védi.</p>")
                : QStringLiteral("<p>✓ A jelenlegi PDF-változatot érvényes aláírás lefedi.</p>");
        } else if (coverage.value(QStringLiteral("assessment")).toString() == QStringLiteral("TECHNICAL_ONLY")) {
            m_information->setText(QStringLiteral("ⓘ A PDF lezárása után %1 bájt felismert technikai többlet található. Az aláírás érvényes; a többlet nem része az aláírt adatoknak. Részletek az exportált jelentésben.")
                .arg(coverage.value(QStringLiteral("technicalTail")).toObject().value(QStringLiteral("unsignedBytes")).toInt()));
            m_information->show();
        } else if (!signatures.isEmpty() || !timestamps.isEmpty()) {
            html += QStringLiteral("<p><b>⚠ A jelenlegi PDF teljes lefedettsége nem igazolt. Az aláírás vagy időbélyeg korábbi változatra vonatkozhat.</b></p>");
        }
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
        const auto error = exportReports(dir.path());
        if (!error.isEmpty()) { QMessageBox::warning(this, windowTitle(), error); return; }
        QMessageBox::information(this, windowTitle(), QStringLiteral("A jelentések elkészültek:\n") + dir.path());
    }
    QString exportReports(const QString &directory) const
    {
        QDir dir(directory);
        // Always export a bounded, non-identifying technical summary, even when
        // the engine crashed, timed out or produced no consumable result.
        auto summary = EutlUi::technicalSummary(m_result);
        QJsonObject run{{QStringLiteral("status"), m_runStatus}, {QStringLiteral("elapsedMillis"), m_finishedMillis},
            {QStringLiteral("processCrashed"), m_crashed}, {QStringLiteral("inputBytes"), m_inputSize}};
        if (m_exitCode != -1) run.insert(QStringLiteral("exitCode"), m_exitCode);
        // Failure codes are internal constants, never exception messages or paths.
        run.insert(QStringLiteral("failureCode"), m_failureCode == QStringLiteral("CACHE_FAILURE") ? m_failureCode
            : m_failureCode == QStringLiteral("TIMEOUT") ? m_failureCode
            : m_failureCode == QStringLiteral("ENGINE_START_FAILED") ? m_failureCode
            : m_runStatus == QStringLiteral("ERROR") ? QStringLiteral("ENGINE_RESULT_ERROR") : QString());
        summary.insert(QStringLiteral("run"), run);
        const auto bytes = QJsonDocument(summary).toJson(QJsonDocument::Indented);
        for (const auto &entry : {QStringLiteral("summary.json"), QStringLiteral("summary.txt")}) {
            QSaveFile target(dir.filePath(entry));
            const auto data = entry.endsWith(QStringLiteral(".txt"))
                ? QStringLiteral("Okular EUTL – technikai összefoglaló\nSzemélynevek, tanúsítványtartalom, helyi útvonalak és szabad szöveges hibaüzenetek nélkül.\n\n").toUtf8() + bytes : bytes;
            if (!target.open(QIODevice::WriteOnly) || target.write(data) != data.size() || !target.commit()) {
                return QStringLiteral("A technikai összefoglaló mentése sikertelen.");
            }
        }
        QStringList files = {QStringLiteral("result.json"), QStringLiteral("simple-report.xml"), QStringLiteral("detailed-report.xml"), QStringLiteral("diagnostic-data.xml"), QStringLiteral("etsi-report.xml"), QStringLiteral("engine.log")};
        for (const auto &name : files) {
            const QString path = m_run->filePath(name);
            if (QFile::exists(path) && !QFile::copy(path, dir.filePath(name))) {
                return QStringLiteral("A jelentések mentése csak részben sikerült.");
            }
        }
        return {};
    }
    static constexpr qint64 MaxInputBytes = 2LL * 1024 * 1024 * 1024 - 1;
    struct SnapshotState { QString hash, error; qint64 size = 0; };
    std::shared_ptr<QTemporaryDir> m_run = std::make_shared<QTemporaryDir>();
    QPointer<QThread> m_snapshot;
    QLabel *m_heading = nullptr;
    QLabel *m_timestampHeading = nullptr;
    QLabel *m_information = nullptr;
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
    QJsonObject m_result;
    QString m_runStatus = QStringLiteral("ERROR"), m_failureCode;
    qint64 m_inputSize = 0, m_finishedMillis = 0;
    int m_exitCode = -1;
    bool m_crashed = false;
    QString m_hash, m_source;
    bool m_done = false;
};
