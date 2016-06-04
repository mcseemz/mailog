package com.mcseemz;

import com.google.gson.stream.JsonReader;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;

/**
 * Template instanse and initializations
 * Created by mcseem on 08.08.15.
 */
public class Template {
    String name = "default";
    /**version for reaload*/
    String version = "";
    int report_records;
    String report_subject = "";
    String report_to = "";
    String report_mailbox = "";
    /**
     * сообщения, который пойдут в отчет
     */
    List<Map<String, String>> currentRecords = new ArrayList<>();
    List<Section> sections = new ArrayList<>();
    /**alerts list for this template*/
    List<Alert> alerts = new ArrayList<>();
    Set<Message> currentMessages = new HashSet<>();
    /**
     * отправлять ли письма в принципе. Отклчюается, если нужно собрать пписьма по каким-то правилам и удалить
     */
    public boolean toSend = true;
    public boolean toDelete = true;
    public boolean toUnseen = false;
    public String mimetype = "text/plain";

    /**блокирующий объект. по нему блокируем при обновлениишаблона*/
    public final Boolean block = Boolean.TRUE;

    static List<Template> initTemplates() throws IOException {
        final List<Template> templates = new ArrayList<>();

        Files.walkFileTree(Paths.get(Main.workdir + "/templates"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                logger.info("processing template file: " + file.getFileName());
                InputStreamReader ireader = new InputStreamReader(Files.newInputStream(file));
                JsonReader reader = new JsonReader(ireader);

                Template template = parseTemplate(reader);
                templates.add(template);

                return FileVisitResult.CONTINUE;
            }

        });
        return templates;
    }

    static Template parseTemplate(JsonReader reader) throws IOException {
        Template template = new Template();
        List<String> report_format = new ArrayList<>();

        reader.beginObject();
        while (reader.hasNext()) {
            String tokenname = reader.nextName();
            if (tokenname.equals("name")) template.name = reader.nextString();
            if (tokenname.equals("version")) template.version = reader.nextString();
            if (tokenname.equals("report")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    tokenname = reader.nextName();

                    if (tokenname.equals("records")) template.report_records = reader.nextInt();
                    if (tokenname.equals("subject")) template.report_subject = reader.nextString();
                    if (tokenname.equals("to")) template.report_to = reader.nextString();
                    if (tokenname.equals("mailbox")) template.report_mailbox = reader.nextString();
                    if (tokenname.equals("mimetype")) template.mimetype = reader.nextString();
                    if (tokenname.equals("send")) template.toSend = reader.nextBoolean();
                    if (tokenname.equals("delete")) template.toDelete = reader.nextBoolean();
                    if (tokenname.equals("seen")) template.toUnseen = !reader.nextBoolean();
                    if (tokenname.equals("format")) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            report_format.add(reader.nextString());
                        }
                        reader.endArray();
                    }
                }
                reader.endObject();
            }
            if (tokenname.equals("alert")) template.alerts.add(Alert.initAlert(reader));
        }
        reader.endObject();

        //побить по секциям
        int startSection = 0;
        int endSection = 0;
        while (endSection < report_format.size()) {
            if (report_format.get(endSection).contains(Main.KEYWORD_SECTION)) {
                Section section = new Section();
                section.format.addAll(report_format.subList(startSection, endSection + 1));
                template.sections.add(section);
                startSection = endSection + 1;
                endSection = startSection;
            }
            endSection++;
        }
        Section section1 = new Section();
        section1.format.addAll(report_format.subList(startSection, endSection));
        template.sections.add(section1);

        logger.info("template:sections");
        for (Section section : template.sections) {
            logger.info("template:section format");

            for (String line : section.format) {
                logger.info(line);

                Matcher fieldsMatcher = Main.fieldsFormatPattern.matcher(line);
                while (fieldsMatcher.find())
                    if (!(fieldsMatcher.group(1)+fieldsMatcher.group(2)).contains(Main.KEYWORD_SECTION))
                        section.usedFields.add(fieldsMatcher.group(1)+fieldsMatcher.group(2)+(fieldsMatcher.group(3)==null ? "" : fieldsMatcher.group(3)));
            }
            if (section.usedFields.isEmpty()) {
                section.oneTime = true; //один раз выводим
            }

            logger.info("template:section onetime:" + section.oneTime);
            logger.info("template:usedfields:" + section.usedFields);

            section.usedFields.add(Main.KEYWORD_DATE);
            section.usedFields.add(Main.KEYWORD_TIME);
            section.usedFields.add(Main.KEYWORD_FOLDER);
            section.usedFields.add(Main.KEYWORD_FROM);
        }

        return template;
    }


    public static class Section {
        List<String> format = new ArrayList<>();
        int records = 0;
        StringBuilder report = new StringBuilder();
        Set<String> usedFields = new HashSet<>();
        boolean oneTime = false;
    }

    /**
     * добавление записи
     *
     * @param record карта со значениями
     * @return true - все обработалось
     */
    public boolean addRecord(Map<String, String> record, Message message) {
        for (Map.Entry<String, String> entry : record.entrySet()) {
            logger.info(entry.getKey() + "=" + entry.getValue());
        }

        //добавить запись
        currentRecords.add(record);
        currentMessages.add(message);
        logger.info("template: record added. Now:" + currentRecords.size() + " / " + currentMessages.size());

        try {
            record.put(Main.KEYWORD_DATE, date.format(message.getSentDate()));
            record.put(Main.KEYWORD_TIME, time.format(message.getSentDate()));
            record.put(Main.KEYWORD_FROM, message.getFrom().length > 0 ? message.getFrom()[0].toString() : "");
            record.put(Main.KEYWORD_FOLDER, message.getFolder().getFullName());
        } catch (MessagingException e) {
            e.printStackTrace();
        }

        if (!Main.isArchive)
        for (Alert alert : alerts) {
            alert.addRecord(record);
        }


        for (Section section : sections) {
            logger.info("template: section start " + section.format.size() + " " + section.oneTime + " " + section.records);
            if (section.format.isEmpty()) section.records++;    //просто считаем письма
            if (section.oneTime && section.records > 0) continue;   //если вообще нет полей

            boolean notEmpty = false;
            for (String usedField : section.usedFields) {
                usedField = usedField.replaceAll("#.?\\d+","");    //отрезаем цифры
                logger.info("now usedfield: " + usedField);
                if (record.containsKey(usedField.toLowerCase()))  notEmpty = true;
                //если поле обязательное, а его нет
                if (!record.containsKey(usedField.toLowerCase()) && !usedField.toLowerCase().equals(usedField)) {
                    logger.info("template: no required field: " + usedField);
                    notEmpty = false;
                    break;
                }

            }

            if (section.oneTime) notEmpty = true;   //один раз можно

            if (!notEmpty) continue;

            section.records++;

            //вывод секции в отчет
            for (String line : section.format) {
                String data = line;
                if (line.contains(Main.KEYWORD_SECTION)) continue;
                for (String usedField : section.usedFields) {
                    String trimusedField = usedField.replaceAll("#.?\\d+", "");    //отрезаем цифры
                    String format = usedField.replaceAll(".+#(.?\\d+)","$1");
                    if (format.equals(usedField)) format = "";

                    data = data.replace("<" + usedField + ">", String.format("%1$"+format+"s", record.containsKey(trimusedField.toLowerCase()) ? record.get(trimusedField.toLowerCase()) : ""));
                }

                section.report.append(data).append("\n");
            }
        }
        return true;
    }

    public boolean processRecords() {
        return processRecords(false);
    }

    public boolean flush() {
        return processRecords(true);
    }

    /**
     * обработать накопленные записи.
     * запускается отдельно, чтобы срабатывать после окончания обработки письма, и не разбивать письмо на два отчета
     */
    private boolean processRecords(boolean doFlush) {
        //проверить на выполнение условий
        //сколько записей в секциях
        int totalrecords = 0;
        for (Section section : sections) totalrecords += section.records;

        if (totalrecords==0) return false;  //точно ничего не отправляем
        if (totalrecords < this.report_records && !doFlush) return false; //не выполнено условие по размеру

        //собрать отчет
        StringBuilder report = new StringBuilder();
        for (Section section : sections)
            report.append(section.report);

        //отправить
        if (toSend)
            try {
                Main.Mailbox mailbox = Main.mappedmailboxes.get(report_mailbox);
                sendEmail(mailbox, this.report_to, "RPT: "+this.report_subject, report, this.mimetype+"; charset=UTF-8");
            } catch (MessagingException e) {
                e.printStackTrace();
            }

//            System.exit(101);
        //сбросить все
        for (Section section : sections) {
            section.records = 0;
            section.report.setLength(0);
        }

        currentRecords.clear();
        logger.info("currentMessages to delete: " + currentMessages.size());
        for (Message currentMessage : currentMessages) {
            try {
                if (toDelete)
                currentMessage.setFlag(Flags.Flag.DELETED, true);
                else if (toUnseen)
                currentMessage.setFlag(Flags.Flag.SEEN, false);
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        }
        currentMessages.clear();
        return true;
    }

    public static synchronized boolean sendEmail(final Main.Mailbox mailbox, String to, String subject, StringBuilder report, String contenttype) throws MessagingException {
        Properties props = System.getProperties();
        props.setProperty("mail.transport.protocol", "smtp");

        props.setProperty("mail.smtp.auth", String.valueOf(mailbox.smtp_auth));
        props.setProperty("mail.smtp.user", mailbox.mailbox);
        props.setProperty("mail.smtp.host", mailbox.smtp_address);
        props.setProperty("mail.smtp.port", String.valueOf(mailbox.smtp_port));
        props.setProperty("mail.smtp.connectiontimeout", "25000");
        props.setProperty("mail.smtp.timeout", "25000");
        props.setProperty("mail.smtp.starttls.enable", String.valueOf(mailbox.starttls));
        if (mailbox.starttls)
            props.setProperty("mail.smtp.ssl.trust", "*");

        Session mailSession = mailbox.smtp_auth
                ? Session.getInstance(props,
                new javax.mail.Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(mailbox.mailbox, mailbox.password);
                    }
                })
                : Session.getInstance(props)
                ;
        mailSession.setDebug(true);
        Transport transport = mailSession.getTransport();

        MimeMessage message = new MimeMessage(mailSession);
        message.setSubject(subject, "UTF-8");
        message.setContent(report.toString(), contenttype);
        message.addRecipient(Message.RecipientType.TO,
                new InternetAddress(to));

        transport.connect();
        transport.sendMessage(message,
                message.getRecipients(Message.RecipientType.TO));
        transport.close();

        return true;
    }

    private static SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy");
    private static SimpleDateFormat time = new SimpleDateFormat("HH.mm.ss");

    //todo релоад шаблонов без рестарта
    /*
    алерт имеет имя
    правило, шаблон и алерт имеют поле version
    при совпадении имени и версии пропускаем
    при совпадении имени и изменении версии
        находим по имени
        перегружаем поля, кроме накопленных
    при обнаружении нового догружаем в список
     */

    private static final Logger logger =
            Logger.getLogger(Template.class.getName());
}
