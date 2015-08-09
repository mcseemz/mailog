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
import java.util.regex.Matcher;

/**
 * Created by mcseem on 08.08.15.
 */
public class Template {
    String name = "";
    int report_records;
    String report_subject = "";
    String report_to = "";
    String report_mailbox = "";
    List<String> report_format = new ArrayList<>();
    /**
     * сообщения, который пойдут в отчет
     */
    List<Map<String, String>> currentRecords = new ArrayList<>();
    List<Section> sections = new ArrayList<>();
    /**
     * отправлять ли письма в принципе. Отклчюается, если нужно собрать пписьма по каким-то правилам и удалить
     */
    public boolean toSend = true;

    static List<Template> initTemplates() throws IOException {
        final List<Template> templates = new ArrayList<>();

        Files.walkFileTree(Paths.get(Main.workdir + "/templates"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                System.out.println("processing template file: " + file.getFileName());
                InputStreamReader ireader = new InputStreamReader(Files.newInputStream(file));
                Template template = new Template();

                JsonReader reader = new JsonReader(ireader);
                reader.beginObject();
                while (reader.hasNext()) {
                    String tokenname = reader.nextName();
                    if (tokenname.equals("name")) template.name = reader.nextString();
                    if (tokenname.equals("report")) {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            tokenname = reader.nextName();

                            if (tokenname.equals("records")) template.report_records = reader.nextInt();
                            if (tokenname.equals("subject")) template.report_subject = reader.nextString();
                            if (tokenname.equals("to")) template.report_to = reader.nextString();
                            if (tokenname.equals("mailbox")) template.report_mailbox = reader.nextString();
                            if (tokenname.equals("send")) template.toSend = reader.nextBoolean();
                            if (tokenname.equals("format")) {
                                reader.beginArray();
                                while (reader.hasNext()) {
                                    template.report_format.add(reader.nextString());
                                }
                                reader.endArray();
                            }
                        }
                        reader.endObject();
                    }
                }
                reader.endObject();
                templates.add(template);

                //побить по секциям
                int startSection = 0;
                int endSection = 0;
                while (endSection < template.report_format.size()) {
                    if (template.report_format.get(endSection).contains(Main.KEYWORD_SECTION)) {
                        Section section = new Section();
                        section.format.addAll(template.report_format.subList(startSection, endSection + 1));
                        template.sections.add(section);
                        startSection = endSection + 1;
                        endSection = startSection;
                    }
                    endSection++;
                }
                Section section1 = new Section();
                section1.format.addAll(template.report_format.subList(startSection, endSection));
                template.sections.add(section1);

                System.out.println("template:sections");
                for (Section section : template.sections) {
                    System.out.println("template:section format");

                    for (String line : section.format) {
                        System.out.println(line);

                        Matcher fieldsMatcher = Main.fieldsFormatPattern.matcher(line);
                        while (fieldsMatcher.find())
                            if (!fieldsMatcher.group(1).contains(Main.KEYWORD_SECTION))
                                section.usedFields.add(fieldsMatcher.group(1)+(fieldsMatcher.group(2)==null ? "" : fieldsMatcher.group(2)));
                    }
                    if (section.usedFields.isEmpty()) {
                        section.oneTime = true; //один раз выводим
                    }

                    System.out.println("template:section onetime:" + section.oneTime);
                    System.out.println("template:usedfields:" + section.usedFields);

                    section.usedFields.add(Main.KEYWORD_DATE);
                    section.usedFields.add(Main.KEYWORD_TIME);
                    section.usedFields.add(Main.KEYWORD_FOLDER);
                    section.usedFields.add(Main.KEYWORD_FROM);
                }


                return FileVisitResult.CONTINUE;
            }

        });
        return templates;
    }


    static class Section {
        List<String> format = new ArrayList<>();
        int records = 0;
        StringBuilder report = new StringBuilder();
        Set<String> usedFields = new HashSet<>();
        boolean oneTime = false;
    }


    Set<Message> currentMessages = new HashSet<>();

    /**
     * добавление записи
     *
     * @param record карта со значениями
     * @return true - все обработалось
     */
    public boolean addRecord(Map<String, String> record, Message message) {
        for (Map.Entry<String, String> entry : record.entrySet()) {
            System.out.println(entry.getKey() + "=" + entry.getValue());
        }

        SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy");
        SimpleDateFormat time = new SimpleDateFormat("HH.mm.ss");

        //добавить запись
        currentRecords.add(record);
        currentMessages.add(message);
        System.out.println("template: record added. Now:" + currentRecords.size() + " / " + currentMessages.size());

        for (Section section : sections) {
            System.out.println("template: section start "+section.format.size()+" "+section.oneTime+" "+section.records);
            if (section.format.isEmpty()) section.records++;    //просто считаем письма
            if (section.oneTime && section.records > 0) continue;   //если вообще нет полей

            try {
                record.put(Main.KEYWORD_DATE, date.format(message.getSentDate()));
                record.put(Main.KEYWORD_TIME, time.format(message.getSentDate()));
                record.put(Main.KEYWORD_FROM, message.getFrom().length > 0 ? message.getFrom()[0].toString() : "");
                record.put(Main.KEYWORD_FOLDER, message.getFolder().getFullName());
            } catch (MessagingException e) {
                e.printStackTrace();
            }

            boolean notEmpty = false;
            for (String usedField : section.usedFields) {
                usedField = usedField.replaceAll("#.?\\d+","");    //отрезаем цифры
                System.out.println("now usedfield: " + usedField);
                if (record.containsKey(usedField.toLowerCase()))  notEmpty = true;
                //если поле обязательное, а его нет
                if (!record.containsKey(usedField.toLowerCase()) && !usedField.toLowerCase().equals(usedField)) {
                    System.out.println("template: no required field: "+usedField);
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

                //todo форматировать по sprintf
                //проблема в передаче форматирования в шаблоне
                section.report.append(data).append("\n");
            }
        }
        //todo алерты
        //алерты возможны, если он постоянно мониторит активность
        //подверить store.addFolderListener(); на папку
        //как совмещать долгий прогон и listener с алертами? Раздельные механизмы
        //можно listenermode - тогда скармливать правилам не все, а пойманные в listener данные
        return true;
    }

    /**
     * обработать накопленные записи.
     * запускается отдельно, чтобы срабатывать после окончания обработки письма, и не разбивать письмо на два отчета
     */
    public boolean processRecords() {
        //проверить на выполнение условий
        //сколько записей в секциях
        int totalrecords = 0;
        for (Section section : sections) totalrecords += section.records;

        if (totalrecords < this.report_records) return false; //не выполнено условие по размеру

        //собрать отчет
        StringBuilder report = new StringBuilder();
        for (Section section : sections)
            report.append(section.report);

        //отправить
        if (toSend)
            try {
                Main.Mailbox mailbox = Main.mappedmailboxes.get(report_mailbox);
                sendEmail(mailbox, this.report_to, this.report_subject, report, "text/plain; charset=UTF-8");
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
        System.out.println("currentMessages to delete: " + currentMessages.size());
        for (Message currentMessage : currentMessages) {
            try {
                currentMessage.setFlag(Flags.Flag.DELETED, true);
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        }
        currentMessages.clear();
        return true;
    }

    private synchronized boolean sendEmail(final Main.Mailbox mailbox, String to, String subject, StringBuilder report, String contenttype) throws MessagingException {
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

        Session mailSession = Session.getInstance(props,
                new javax.mail.Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(mailbox.mailbox, mailbox.password);
                    }
                });
        mailSession.setDebug(true);
        Transport transport = mailSession.getTransport();

        MimeMessage message = new MimeMessage(mailSession);
        message.setSubject("RPT: " + subject, "UTF-8");
        message.setContent(report.toString(), contenttype);
        message.addRecipient(Message.RecipientType.TO,
                new InternetAddress(to));

        transport.connect();
        transport.sendMessage(message,
                message.getRecipients(Message.RecipientType.TO));
        transport.close();

        return true;
    }

}
