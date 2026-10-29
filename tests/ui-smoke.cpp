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
        const QByteArray bytes = f.readAll();
        QThread::msleep(1300);
        QJsonObject root{{"schema",1},{"sha256",QString::fromLatin1(QCryptographicHash::hash(bytes,QCryptographicHash::Sha256).toHex())},{"status","COMPLETED"}};
        QJsonArray signatures;
        const auto add = [&](const char *ind) { signatures.append(QJsonObject{{"signedBy","Teszt Aláíró"},{"indication",ind},{"qualification","QESIG"}}); };
        if (bytes.contains("MIXED")) { add("TOTAL_PASSED"); add("INDETERMINATE"); }
        else if (bytes.contains("INVALID")) add("TOTAL_FAILED");
        else if (bytes.contains("\nUNKNOWN")) add("INDETERMINATE");
        else if (bytes.contains("EMPTY")) root["status"]="NO_SIGNATURES";
        else add("TOTAL_PASSED");
        root["signatures"] = signatures;
        if (!bytes.contains("NO_COVERAGE")) root["documentCoverage"] = QJsonObject{{"status", bytes.contains("PARTIAL") ? "PARTIAL" : bytes.contains("COVERAGE_UNKNOWN") ? "UNKNOWN" : "FULL"}};
        if (bytes.contains("WRONG_HASH")) root["sha256"]="wrong";
        QFile out(QString::fromLocal8Bit(argv[2])+"/result.json");out.open(QIODevice::WriteOnly);
        out.write(bytes.contains("BAD_JSON") ? QByteArray("invalid") : QJsonDocument(root).toJson());
        return 0;
    }
    QApplication app(argc,argv); app.setStyle(QStringLiteral("Fusion"));
    if (qEnvironmentVariableIsSet("EUTL_UI_DARK")) {
        QPalette palette;
        palette.setColor(QPalette::Window,QColor("#202530"));
        palette.setColor(QPalette::Base,QColor("#151921"));
        palette.setColor(QPalette::Text,Qt::white);
        palette.setColor(QPalette::WindowText,Qt::white);
        palette.setColor(QPalette::Button,QColor("#303642"));
        palette.setColor(QPalette::ButtonText,Qt::white);
        app.setPalette(palette);
    }
    QTemporaryDir temp;
    if (qEnvironmentVariableIsSet("EUTL_UI_SCREENSHOTS")) { temp.setAutoRemove(false); std::cout << "Screenshots: " << temp.path().toStdString() << std::endl; }
    try {
        for (const auto &mode : {"VALID", "INVALID", "UNKNOWN", "MIXED", "EMPTY", "BAD_JSON", "WRONG_HASH", "PARTIAL", "NO_COVERAGE", "COVERAGE_UNKNOWN"}) {
            const QString file=temp.filePath(QString::fromLatin1(mode)+".pdf");
            QFile f(file); f.open(QIODevice::WriteOnly); f.write(QByteArray("%PDF-1.7\n")+mode);f.close();
            EutlDialog dialog(file,nullptr,QCoreApplication::applicationFilePath());dialog.show();
            auto *progress=dialog.findChild<QProgressBar *>(QStringLiteral("eutl_progress"));
            auto *heading=dialog.findChild<QLabel *>(QStringLiteral("eutl_status"));
            require(progress && progress->minimum()==0 && progress->maximum()==0,"busy indicator missing");
            if (qEnvironmentVariableIsSet("EUTL_UI_SCREENSHOTS") && QString::fromLatin1(mode)=="VALID") { app.processEvents(); dialog.grab().save(temp.filePath("running.png")); }
            QElapsedTimer wait;wait.start();
            while (!progress->isHidden() && wait.elapsed()<6000) { app.processEvents();QThread::msleep(10); }
            require(progress->isHidden(),"busy indicator did not finish");
            const QString text=heading->text();
            if (QString::fromLatin1(mode)=="VALID") {
                require(text==QStringLiteral("✓ ÉRVÉNYES"),"valid verdict incorrect");
                auto *details=dialog.findChild<QTextBrowser *>(QStringLiteral("eutl_details"));require(details->isHidden(),"details must initially be collapsed");
                if (qEnvironmentVariableIsSet("EUTL_UI_SCREENSHOTS")) dialog.grab().save(temp.filePath("valid.png"));
            } else if (QString::fromLatin1(mode)=="INVALID") require(text==QStringLiteral("✗ ÉRVÉNYTELEN"),"invalid verdict incorrect");
            else if (QString::fromLatin1(mode)=="EMPTY") require(text.contains(QStringLiteral("NINCS FELISMERT")),"unsigned file shown as valid");
            else if (QString::fromLatin1(mode)=="PARTIAL" || QString::fromLatin1(mode)=="NO_COVERAGE" || QString::fromLatin1(mode)=="COVERAGE_UNKNOWN") require(text.contains(QStringLiteral("LEFEDETTSÉGE NEM IGAZOLT")),"coverage warning missing");
            else require(text==QStringLiteral("⚠ NEM ELLENŐRIZHETŐ"),"incomplete result shown as conclusive");
            std::cout << mode << ": OK\n";
        }
        QFile file(temp.filePath("cancel.pdf"));file.open(QIODevice::WriteOnly);file.write("%PDF-1.7\nVALID");file.close();
        auto *dialog=new EutlDialog(file.fileName(),nullptr,QCoreApplication::applicationFilePath());dialog->show();app.processEvents();
        QPointer<QProcess> process=dialog->findChild<QProcess *>();
        require(process,"process fixture missing");delete dialog;require(process.isNull(),"subprocess not released on close");
        std::cout << "CANCEL: OK\n";
    } catch (const std::exception &e) { std::cerr<<e.what()<<'\n'; return 1; }
    return 0;
}
