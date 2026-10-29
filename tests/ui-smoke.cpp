// SPDX-License-Identifier: GPL-2.0-or-later
#include "eutldialog.h"
#include <QApplication>
#include <QCoreApplication>
#include <QThread>
#include <QPointer>
#include <iostream>
#include <stdexcept>
static void require(bool b, const char *message) { if (!b) throw std::runtime_error(message); }
int main(int argc, char **argv) {
    // Subprocess fixture: same arguments as the real engine, no network requests.
    if (argc == 4) {
        QFile f(QString::fromLocal8Bit(argv[1])); f.open(QIODevice::ReadOnly);
        const QByteArray bytes = f.read(1024);
        f.seek(0); QCryptographicHash hash(QCryptographicHash::Sha256); hash.addData(&f);
        QThread::msleep(1300);
        QJsonObject root{{QStringLiteral("schema"),2},{QStringLiteral("sha256"),QString::fromLatin1(hash.result().toHex())},{QStringLiteral("status"),QStringLiteral("COMPLETED")}};
        QJsonArray signatures;
        const auto add = [&](const char *ind) { signatures.append(QJsonObject{{QStringLiteral("signedBy"),QStringLiteral("Teszt Aláíró")},{QStringLiteral("indication"),QString::fromLatin1(ind)},{QStringLiteral("qualification"),QStringLiteral("QESIG")}}); };
        if (bytes.contains("MIXED")) { add("TOTAL_PASSED"); add("INDETERMINATE"); }
        else if (bytes.contains("\nINVALID")) add("TOTAL_FAILED");
        else if (bytes.contains("\nUNKNOWN")) add("INDETERMINATE");
        else if (bytes.contains("EMPTY")) root[QStringLiteral("status")]=QStringLiteral("NO_SIGNATURES");
        else add("TOTAL_PASSED");
        QJsonArray timestamps;
        if (bytes.contains("TS_")) {
            const bool invalid = bytes.contains("TS_INVALID");
            const bool unknown = bytes.contains("TS_UNKNOWN");
            timestamps.append(QJsonObject{{QStringLiteral("id"),QStringLiteral("T-1")},{QStringLiteral("indication"),invalid ? QStringLiteral("FAILED") : unknown ? QStringLiteral("INDETERMINATE") : QStringLiteral("PASSED")},
                {QStringLiteral("messageImprintFound"),true},{QStringLiteral("messageImprintIntact"),!invalid},{QStringLiteral("productionTime"),QStringLiteral("2026-10-28T07:00:00.000Z")},
                {QStringLiteral("producedBy"),QStringLiteral("Teszt TSA")},{QStringLiteral("qualification"),QStringLiteral("QTSA")},{QStringLiteral("coversSignature"),true},{QStringLiteral("documentTimestamp"),true},{QStringLiteral("coverage"),QStringLiteral("FULL")}});
            if (!signatures.isEmpty()) { auto signature = signatures[0].toObject(); signature[QStringLiteral("timestampIds")] = QJsonArray{QStringLiteral("T-1")}; signatures[0] = signature; }
            if (bytes.contains("TS_ONLY")) signatures = {};
        }
        root[QStringLiteral("timestamps")] = timestamps;
        root[QStringLiteral("signatures")] = signatures;
        if (!bytes.contains("NO_COVERAGE")) root[QStringLiteral("documentCoverage")] = QJsonObject{{QStringLiteral("status"), bytes.contains("PARTIAL") ? QStringLiteral("PARTIAL") : bytes.contains("COVERAGE_UNKNOWN") ? QStringLiteral("UNKNOWN") : QStringLiteral("FULL")}};
        if (bytes.contains("LEGACY_SCHEMA")) root[QStringLiteral("schema")]=1;
        if (bytes.contains("WRONG_HASH")) root[QStringLiteral("sha256")]=QStringLiteral("wrong");
        QFile out(QString::fromLocal8Bit(argv[2])+QStringLiteral("/result.json"));out.open(QIODevice::WriteOnly);
        out.write(bytes.contains("BAD_JSON") ? QByteArray("invalid") : QJsonDocument(root).toJson());
        return 0;
    }
    QApplication app(argc,argv); app.setStyle(QStringLiteral("Fusion"));
    if (qEnvironmentVariableIsSet("EUTL_UI_DARK")) {
        QPalette palette;
        palette.setColor(QPalette::Window,QColor(QStringLiteral("#202530")));
        palette.setColor(QPalette::Base,QColor(QStringLiteral("#151921")));
        palette.setColor(QPalette::Text,Qt::white);
        palette.setColor(QPalette::WindowText,Qt::white);
        palette.setColor(QPalette::Button,QColor(QStringLiteral("#303642")));
        palette.setColor(QPalette::ButtonText,Qt::white);
        app.setPalette(palette);
    }
    QTemporaryDir temp;
    if (qEnvironmentVariableIsSet("EUTL_UI_SCREENSHOTS")) { temp.setAutoRemove(false); std::cout << "Screenshots: " << temp.path().toStdString() << std::endl; }
    try {
        for (const auto &mode : {"VALID", "INVALID", "UNKNOWN", "MIXED", "EMPTY", "BAD_JSON", "WRONG_HASH", "PARTIAL", "NO_COVERAGE", "COVERAGE_UNKNOWN", "TS_VALID", "TS_INVALID", "TS_UNKNOWN", "TS_ONLY", "LEGACY_SCHEMA", "OVERSIZED", "LARGE"}) {
            const QString only = qEnvironmentVariable("EUTL_UI_CASE");
            if (!only.isEmpty() && only != QString::fromLatin1(mode)) continue;
            const QString file=temp.filePath(QString::fromLatin1(mode)+QStringLiteral(".pdf"));
            QFile f(file); f.open(QIODevice::WriteOnly); f.write(QByteArray("%PDF-1.7\n")+mode);
            if (QString::fromLatin1(mode)== QStringLiteral("LARGE")) require(f.resize(256LL*1024*1024+1),"large fixture resize failed");
            if (QString::fromLatin1(mode)== QStringLiteral("OVERSIZED")) require(f.resize(2LL*1024*1024*1024),"oversized fixture resize failed");
            f.close();
            EutlDialog dialog(file,nullptr,QCoreApplication::applicationFilePath());dialog.show();
            auto *progress=dialog.findChild<QProgressBar *>(QStringLiteral("eutl_progress"));
            auto *heading=dialog.findChild<QLabel *>(QStringLiteral("eutl_status"));
            require(progress && progress->minimum()==0 && progress->maximum()==0,"busy indicator missing");
            if (qEnvironmentVariableIsSet("EUTL_UI_SCREENSHOTS") && QString::fromLatin1(mode)== QStringLiteral("VALID")) { app.processEvents(); dialog.grab().save(temp.filePath(QStringLiteral("running.png"))); }
            QElapsedTimer wait;wait.start();
            while (!progress->isHidden() && wait.elapsed()<(QString::fromLatin1(mode)== QStringLiteral("LARGE") ? 180000 : 6000)) { app.processEvents();QThread::msleep(10); }
            require(progress->isHidden(),"busy indicator did not finish");
            const QString text=heading->text();
            if (QString::fromLatin1(mode).startsWith(QStringLiteral("TS_")) && QString::fromLatin1(mode)!= QStringLiteral("TS_ONLY")) {
                require(text==QStringLiteral("✓ ÉRVÉNYES"),"timestamp status incorrectly replaced signature validity");
                const auto summary=dialog.findChild<QTextBrowser *>(QStringLiteral("eutl_summary"))->toPlainText();
                require(summary.contains(QStringLiteral("Teszt TSA")),"TSA missing from summary");
                require(summary.contains(QStringLiteral("minősített")),"timestamp qualification missing");
                if (QString::fromLatin1(mode)== QStringLiteral("TS_INVALID")) require(summary.contains(QStringLiteral("ÉRVÉNYTELEN")),"bad timestamp hidden");
                if (QString::fromLatin1(mode)== QStringLiteral("TS_UNKNOWN")) require(summary.contains(QStringLiteral("NEM ELLENŐRIZHETŐ")),"unknown timestamp hidden");
            } else if (QString::fromLatin1(mode)== QStringLiteral("TS_ONLY")) {
                require(text.contains(QStringLiteral("NINCS FELISMERT")),"timestamp mistaken for a signer");
                require(dialog.findChild<QTextBrowser *>(QStringLiteral("eutl_summary"))->toPlainText().contains(QStringLiteral("Teszt TSA")),"standalone timestamp lost");
            } else if (QString::fromLatin1(mode)== QStringLiteral("VALID") || QString::fromLatin1(mode)== QStringLiteral("LARGE")) {
                require(text==QStringLiteral("✓ ÉRVÉNYES"),"valid verdict incorrect");
                auto *details=dialog.findChild<QTextBrowser *>(QStringLiteral("eutl_details"));require(details->isHidden(),"details must initially be collapsed");
                auto *summary=dialog.findChild<QTextBrowser *>(QStringLiteral("eutl_summary"));
                require(dialog.findChild<QLabel *>(QStringLiteral("eutl_timestamp_status"))->text().contains(QStringLiteral("NINCS SZOLGÁLTATÓI IDŐBÉLYEG")),"missing timestamp not disclosed");
                require(!summary->toPlainText().contains(QStringLiteral("SHA-256")),"technical details leaked into default view");
                auto *toggle=dialog.findChild<QPushButton *>(QString(), Qt::FindDirectChildrenOnly);
                for (auto *button : dialog.findChildren<QPushButton *>()) if (button->text()==QStringLiteral("Részletek megjelenítése")) toggle=button;
                require(toggle,"detail toggle missing"); toggle->click(); require(!details->isHidden(),"details not expandable"); toggle->click();
                if (qEnvironmentVariableIsSet("EUTL_UI_SCREENSHOTS")) { app.processEvents(); dialog.grab().save(temp.filePath(QStringLiteral("valid.png"))); }
            } else if (QString::fromLatin1(mode)== QStringLiteral("INVALID")) require(text==QStringLiteral("✗ ÉRVÉNYTELEN"),"invalid verdict incorrect");
            else if (QString::fromLatin1(mode)== QStringLiteral("EMPTY")) require(text.contains(QStringLiteral("NINCS FELISMERT")),"unsigned file shown as valid");
            else if (QString::fromLatin1(mode)== QStringLiteral("PARTIAL") || QString::fromLatin1(mode)== QStringLiteral("NO_COVERAGE") || QString::fromLatin1(mode)== QStringLiteral("COVERAGE_UNKNOWN")) require(text.contains(QStringLiteral("LEFEDETTSÉGE NEM IGAZOLT")),"coverage warning missing");
            else require(text==QStringLiteral("⚠ NEM ELLENŐRIZHETŐ"),"incomplete result shown as conclusive");
            std::cout << mode << ": OK\n";
        }
        QFile file(temp.filePath(QStringLiteral("cancel.pdf")));file.open(QIODevice::WriteOnly);file.write("%PDF-1.7\nVALID");file.close();
        auto *dialog=new EutlDialog(file.fileName(),nullptr,QCoreApplication::applicationFilePath());dialog->show();app.processEvents();
        QPointer<QProcess> process=dialog->findChild<QProcess *>();
        require(process,"process fixture missing");delete dialog;require(process.isNull(),"subprocess not released on close");
        std::cout << "CANCEL: OK\n";
    } catch (const std::exception &e) { std::cerr<<e.what()<<'\n'; return 1; }
    return 0;
}
