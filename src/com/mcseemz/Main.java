package com.mcseemz;

import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    public static void main(String[] args) throws IOException {

        if (args.length>1) workdir = args[1];
        else workdir = System.getProperty("user.dir")+"/data";

        System.out.println("using workdir:"+workdir);

        //инициализировать ящики
        Mailbox[] mailboxes = initMailboxes();

        for (Mailbox mailbox : mailboxes)
            mappedmailboxes.put(mailbox.mailbox, mailbox);

        //инициализировать шаблоны
        List<Template> templates = initTemplates();

        //инициализировать правила
        List<Rule> rules = initRules();

        Map<String, List<Rule>> mappedrules = new HashMap<>();

        //сгруппировать правила по ящикам и папкам
        for (Rule rule : rules) {
            String key = rule.mailbox+"#"+rule.folder;
            if (!mappedrules.containsKey(key))
                mappedrules.put(key, new ArrayList<Rule>());
            mappedrules.get(key).add(rule);
        }

        //зпросить пароли для всех ящиков
        for (Mailbox mailbox : mailboxes) {
            if (mailbox.password.isEmpty()) {
                Console console = System.console();
                if (console==null) System.out.println("console is null");
                else {
                    console.writer().println("enter password for: " + mailbox.mailbox);
                    char[] chars = console.readPassword();
                    mailbox.password = new String(chars);
                }
            }
        }

        ExecutorService executorService = Executors.newFixedThreadPool(5);

        //запустить основной поток
        for (Map.Entry<String, List<Rule>> entry : mappedrules.entrySet()) {
            Process process = new Process(mappedmailboxes.get(entry.getValue().get(0).mailbox), entry.getValue().get(0).folder, templates, entry.getValue());

            futureList.add(executorService.submit(process));
        }
        //проверка что все потоки отработали
//        executorService.
        while (true) {
            boolean allFinished = true;
            for (Future future : futureList) {
                if (!future.isDone()) allFinished = false;
            }

            if (allFinished) break;
        }
        executorService.shutdownNow();
        System.exit(0);

    }

    private static Mailbox[] initMailboxes() throws FileNotFoundException {
        return new GsonBuilder().create().fromJson(new FileReader(workdir+"/mailbox.json"), Mailbox[].class);
    }

    private static List<Template> initTemplates() throws IOException {
        final List<Template> templates = new ArrayList<>();

        final Pattern fieldsPattern = Pattern.compile("<#?(\\w+)>");

        Files.walkFileTree(Paths.get(workdir + "/templates"), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(    Path file,    BasicFileAttributes attrs) throws IOException {
                System.out.println( "processing template file: "+file.getFileName());
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
                int startSection=0;
                int endSection=0;
                while (endSection < template.report_format.size()) {
                    if (template.report_format.get(endSection).contains(KEYWORD_SECTION)) {
                        Template.Section section = new Template.Section();
                        section.format.addAll(template.report_format.subList(startSection, endSection+1));
                        template.sections.add(section);
                        startSection = endSection+1;
                        endSection = startSection;
                    }
                    endSection++;
                }
                Template.Section section1 = new Template.Section();
                section1.format.addAll(template.report_format.subList(startSection, endSection));
                template.sections.add(section1);

                for (Template.Section section : template.sections) {
                    for (String line : section.format) {
                        Matcher fieldsMatcher = fieldsPattern.matcher(line);
                        while (fieldsMatcher.find()) section.usedFields.add(fieldsMatcher.group(1));
                    }
                    if (section.usedFields.isEmpty()) {
                        section.oneTime = true; //один раз выводим
                    }

                    section.usedFields.add(KEYWORD_DATE);
                    section.usedFields.add(KEYWORD_TIME);
                    section.usedFields.add(KEYWORD_FOLDER);
                    section.usedFields.add(KEYWORD_FROM);
                }

                return FileVisitResult.CONTINUE;
            }

        });
        return templates;
    }

    private static List<Rule> initRules() throws IOException {
        final List<Rule> templates = new ArrayList<>();

        Files.walkFileTree(Paths.get(workdir + "/rules"), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(    Path file,    BasicFileAttributes attrs) throws IOException {
                System.out.println( "processing rule file: "+file.getFileName());

                InputStreamReader ireader = new InputStreamReader(Files.newInputStream(file));
                Rule rule = new Rule();

                JsonReader reader = new JsonReader(ireader);
                reader.beginObject();
                while (reader.hasNext()) {
                    String tokenname = reader.nextName();
                    if (tokenname.equals("name")) rule.name = reader.nextString();
                    if (tokenname.equals("mailbox")) rule.mailbox = reader.nextString().toLowerCase();
                    if (tokenname.equals("folder")) rule.folder = reader.nextString();
                    if (tokenname.equals("subject")) rule.subject = reader.nextString();
                    if (tokenname.equals("from")) rule.from = reader.nextString();
                    if (tokenname.equals("to")) rule.to= reader.nextString();
                    if (tokenname.equals("template")) rule.template= reader.nextString();
                    if (tokenname.equals("body")) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            rule.body.add(reader.nextString());
                        }
                        reader.endArray();
                    }
                    if (tokenname.equals("section")) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            rule.section.add(reader.nextString());
                        }
                        reader.endArray();
                    }
                    if (tokenname.equals("flags")) {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            rule.flags.put(reader.nextName(), reader.nextString());
                        }
                        reader.endObject();
                    }
                }
                reader.endObject();
                templates.add(rule);
                return FileVisitResult.CONTINUE;
            }

        });
        return templates;
    }

    public static class Mailbox {
        String mailbox;
        String password;
        String imap_address;
        int imap_port;
        String smtp_address;
        int smtp_port;
        boolean starttls;
        boolean smtp_auth;
    }

    public static class Template {
        String name = "";
        int report_records;
        String report_subject = "";
        String report_to = "";
        String report_mailbox = "";
        List<String> report_format = new ArrayList<>();
        /** сообщения, который пойдут в отчет */
        List<Map<String, String>> currentRecords = new ArrayList<>();
        List<Section> sections = new ArrayList<>();


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
         * @param record карта со значениями
         * @return true - все обработалось
         */
        public boolean addRecord(Map<String, String> record, Message message) {
            for (Map.Entry<String, String> entry : record.entrySet()) {
                System.out.println(entry.getKey()+"="+entry.getValue());
            }

            SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy");
            SimpleDateFormat time = new SimpleDateFormat("HH.mm.ss");
            try {
                record.put(Main.KEYWORD_DATE, date.format(message.getSentDate()));
                record.put(Main.KEYWORD_TIME, time.format(message.getSentDate()));
                record.put(Main.KEYWORD_FROM, message.getFrom().length>0 ? message.getFrom()[0].toString() : "");
                record.put(Main.KEYWORD_FOLDER, message.getFolder().getFullName());
            } catch (MessagingException e) {
                e.printStackTrace();
            }

            //добавить запись
            currentRecords.add(record);
            currentMessages.add(message);
            System.out.println("template: record added. Now:" + currentRecords.size() + " / " + currentMessages.size());

            for (Section section : sections) {
                if (section.oneTime && section.records>0) continue;

                boolean notEmpty = false;
                for (String usedField : section.usedFields) {
                    System.out.println("now usedfield: "+usedField);
                    if (record.containsKey(usedField) && !usedField.startsWith("#") && record.get(usedField + "_flags").contains("b")) {
                        notEmpty = true;
                        break;
                    }
                }

                if (!notEmpty) continue;

                //вывод секции в отчет
                for (String line : section.format) {
                    String data = line;
                    if (line.contains(KEYWORD_SECTION)) continue;
                    for (String usedField : section.usedFields)
                        data = data.replace("<"+usedField+">", record.containsKey(usedField) ? record.get(usedField) : "");

                    //todo форматировать по sprintf
                    section.report.append(data).append("\n");
                    section.records++;
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
            for (Section section : sections)    totalrecords+=section.records;

            if (totalrecords<this.report_records) return false; //не выполнено условие по размеру

            //собрать отчет
            StringBuilder report = new StringBuilder();
            for (Section section : sections)
                report.append(section.report);

                //отправить
            Mailbox mailbox = mappedmailboxes.get(report_mailbox);
            try {
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
            System.out.println("currentMessages to delete: "+currentMessages.size());
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
            message.setSubject("RPT: "+subject,"UTF-8");
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

    public static class Rule {
        String name;
        String mailbox;
        String folder;
        String subject;
        String from;
        String to;
        List<String> body = new ArrayList<>();
        List<String> section = new ArrayList<>();
        Map<String,String> flags = new HashMap<>();
        String template;
    }

    private static String workdir;

    /** mapping mailbox name to object*/
    private static final Map<String, Mailbox> mappedmailboxes = new HashMap<>();
    private static List<Future> futureList = new ArrayList<>();

    final static String KEYWORD_SECTION = "#section";
    final static String KEYWORD_DATE = "#mdate";
    final static String KEYWORD_TIME = "#mtime";
    final static String KEYWORD_FROM = "#mfrom";
    final static String KEYWORD_FOLDER = "#mfolder";
}