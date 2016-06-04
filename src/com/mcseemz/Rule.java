package com.mcseemz;

import com.google.gson.stream.JsonReader;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule instanse and initialization
 * Created by mcseem on 08.08.15.
 */
public class Rule {
    String name="default";
    String version="";
    String mailbox;
    String folder;
    String subject = "";
    String from = "";
    String to = "";
    final Boolean block = Boolean.TRUE;
    /**
     * возраст сообщения. формат "&lt;3D","&gt;=10H". знак сравнения - величина - единица D/H/M
     */
    String age = "";
    List<List<String>> body = new ArrayList<>();
    List<String> sectionsplitter = new ArrayList<>();
    Map<String, String> flags = new HashMap<>();
    String template;
    Template templateObject;

    static List<Rule> initRules() throws IOException {
        final List<Rule> rules = new CopyOnWriteArrayList<>();

        Files.walkFileTree(Paths.get(Main.workdir + "/rules"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                logger.info("processing rule file: " + file.getFileName());

                InputStreamReader ireader = new InputStreamReader(Files.newInputStream(file));
                JsonReader reader = new JsonReader(ireader);

                Rule rule = parseRule(reader);
                rules.add(rule);
                return FileVisitResult.CONTINUE;
            }

        });
        return rules;
    }

    public String getKey() {
        return mailbox+"#"+folder;
    }

    public static Rule parseRule(JsonReader reader) throws IOException {
        Rule rule = new Rule();

        reader.beginObject();
        while (reader.hasNext()) {
            String tokenname = reader.nextName();
            if (tokenname.equals("name")) rule.name = reader.nextString();
            if (tokenname.equals("version")) rule.version = reader.nextString();
            if (tokenname.equals("mailbox")) rule.mailbox = reader.nextString().toLowerCase();
            if (tokenname.equals("folder")) rule.folder = reader.nextString();
            if (tokenname.equals("subject")) rule.subject = reader.nextString();
            if (tokenname.equals("from")) rule.from = reader.nextString();
            if (tokenname.equals("to")) rule.to = reader.nextString();
            if (tokenname.equals("age")) rule.age = reader.nextString();
            if (tokenname.equals("template")) rule.template = reader.nextString();
            if (tokenname.equals("body")) {
                reader.beginArray();
                List<String> section = new ArrayList<>();

                while (reader.hasNext()) {
                    section.add(reader.nextString());
                }

                rule.body.add(section);
                reader.endArray();
            }
            if (tokenname.equals("bodysplitter")) {
                reader.beginArray();
                while (reader.hasNext()) {
                    rule.sectionsplitter.add(reader.nextString());
                }
                reader.endArray();
            }
            if (tokenname.equals("templatebody")) {
                Template template = Template.parseTemplate(reader);
                rule.template = template.name;
                rule.templateObject = template;
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
        return rule;
    }

    public boolean processMessage(Message message) throws MessagingException {
        String subject = message.getSubject();
        if (subject.startsWith("RPT:")) {
            logger.info("report message detected. Skip");
            return false;
        }
        if (subject.startsWith("ALERT:")) {
            logger.info("alert message detected. Skip");
            return false;
        }

        logger.info("now rule:" + this.name);

        boolean messageProcessed = false;

        try {
            Pattern psubject = Pattern.compile(this.subject);
            Matcher subjectMatcher = psubject.matcher(subject);
            if (!subjectMatcher.find()) {
                logger.info("subject failed:" + subject);
                return false;
            }
            logger.info("subject:" + subject);

            if (!this.from.isEmpty()) {
                Pattern pfrom = Pattern.compile(this.from);
                Matcher fromMatcher = pfrom.matcher(message.getFrom()[0].toString());
                if (!fromMatcher.find()) {
                    logger.info("from failed:" + message.getFrom()[0].toString());
                    return false;
                }
                logger.info("from:" + message.getFrom()[0].toString());
            }

            //todo проверка на другие поля из шапки
//            if (!this.to.isEmpty())
            if (!this.age.isEmpty()) {
                Date sentDate = message.getSentDate();
                Date now = new Date();
                int val = Integer.valueOf(this.age.replaceAll("\\D", ""));

                int tz1 = TimeZone.getDefault().getOffset(sentDate.getTime());
                int tz2 = TimeZone.getDefault().getOffset(now.getTime());
                long diff = 0;
                if (this.age.toUpperCase().endsWith("D")) {
                    diff = Math.abs(now.getTime() - sentDate.getTime() + (tz2 - tz1)) / 86400 / 1000 + 1;
                }
                if (this.age.toUpperCase().endsWith("H")) {
                    diff = Math.abs(now.getTime() - sentDate.getTime() + (tz2 - tz1)) / 3600 / 1000 + 1;
                }

                if (this.age.contains(">") && diff > val) ;
                else if (this.age.contains(">=") && diff >= val) ;
                else if (this.age.contains("<") && diff < val) ;
                else if (this.age.contains("<=") && diff <= val) ;
                else {
                    logger.info("age failed:" + this.age + "/" + diff);
                    return false;
                }
            }

            //разбить тело на секции
            //для каждой секции формируется свое множество полей
            //в это множество попадает и разобранный subject, и метаполя
            //проверка на флаги для полей

            String contentType = message.getContentType();
            StringBuilder text = new StringBuilder();

            logger.info("contentType:" + contentType);


            if (contentType.contains("multipart")) {
                Multipart multipart = (Multipart) message.getContent();
                for (int j = 0; j < multipart.getCount(); j++) {
                    BodyPart bodyPart = multipart.getBodyPart(j);
                    String bodyPartContentType = bodyPart.getContentType();

                    logger.info("bodyPartContentType:" + contentType);

                    if (bodyPartContentType.contains("text/plain")) {
                        text.append(bodyPart.getContent());
                    } else if (bodyPartContentType.contains("text/html")) {
                        text.append(bodyPart.getContent());
                    } else if (bodyPartContentType.contains("multipart")) {
                        multipart = (Multipart) bodyPart.getContent();

                        for (int k = 0; k < multipart.getCount(); k++) {
                            bodyPart = multipart.getBodyPart(k);
                            bodyPartContentType = bodyPart.getContentType();

                            logger.info("bodyPartContentType:" + contentType);

                            if (bodyPartContentType.contains("text/plain")) {
                                text.append(bodyPart.getContent());
                            } else if (bodyPartContentType.contains("text/html")) {
                                text.append(bodyPart.getContent());
                            }
                        }
                    }

                }
            } else if (contentType.contains("text/plain")) {
                text.append(message.getContent());
            } else if (contentType.contains("text/html")) {
                text.append(message.getContent());
            }

            //список всех полей в теле
            Set<String> allbodyfields = new HashSet<>();

            //собрать поля из темы в список
            Matcher fieldMatcher = Main.fieldsPattern.matcher(this.subject);
            List<String> subjectFields = new ArrayList<>();
            while (fieldMatcher.find()) subjectFields.add("_"+fieldMatcher.group(1));


            logger.info("subjectFields found:" + subjectFields);

            //разбиваем по секциям
            //todo бить по всем опмсателям секций, а не только по первому
            String[] sections = this.sectionsplitter.isEmpty()
                    ? new String[]{text.toString()}
                    : text.toString().split(this.sectionsplitter.get(0));

            logger.info("rule:sections size:" + sections.length);

            Map<String, String> subjectrecord = new HashMap<>();
            //кладем поля и темы в запись
            subjectMatcher.reset();
            if (subjectMatcher.find())
            for (int i = 0; i < subjectMatcher.groupCount(); i++) {
                subjectrecord.put(subjectFields.get(i), subjectMatcher.group(i + 1));
                subjectrecord.put(subjectFields.get(i) + "_flags", "s"); //из subject
            }

            section:
            for (String section : sections) {
                logger.info("rule: section:" + section);

                //теперь поля из тела
                bodyrules:
                for (List<String> bodyrules : this.body) {
                    Map<String, String> record = new HashMap<>();
                    record.putAll(subjectrecord);
                    Set<String> bodyrulefields = new HashSet<>();

                    for (String bodyrule : bodyrules) {
                        List<String> bodyFields = new ArrayList<>();

                        //ищем все поля в этом наборе правил
                        Matcher bodyMatcher = Main.fieldsPattern.matcher(bodyrule);
                        while (bodyMatcher.find()) {
                            bodyFields.add("_"+bodyMatcher.group(1));
                            allbodyfields.add("_"+bodyMatcher.group(1));
                        }

                        logger.info("rule:bodyFields found:" + bodyFields);

                        Pattern pbody = Pattern.compile(bodyrule, Pattern.UNICODE_CHARACTER_CLASS+Pattern.MULTILINE+Pattern.DOTALL);
                        Matcher mbody = pbody.matcher(section);
                        boolean isFound = false;
                        while (mbody.find()) {
                            isFound = true;
                            for (int i = 0; i < mbody.groupCount(); i++) {
                                if (flags.get(bodyFields.get(i).toLowerCase()) != null
                                        && flags.get(bodyFields.get(i).toLowerCase()).contains("g")
                                        && record.containsKey(bodyFields.get(i).toLowerCase())
                                        ) {
                                    //накопление данных в поле
                                    record.put(bodyFields.get(i).toLowerCase(), record.get(bodyFields.get(i).toLowerCase()) +
                                            (flags.get(bodyFields.get(i).toLowerCase()).contains("n")
                                            ? "\n"
                                            : " | ") + mbody.group(i + 1));
                                } else record.put(bodyFields.get(i).toLowerCase(), mbody.group(i + 1));
                                record.put(bodyFields.get(i).toLowerCase() + "_flags", "b");
                            }
                        }
                        if (!isFound) logger.info("rule:not found");

                        bodyrulefields.addAll(bodyFields);
                    }

                    //проверка
                    for (String bodyField : bodyrulefields) {
                        if (bodyField.toUpperCase().equals(bodyField)
                                && !record.containsKey(bodyField.toLowerCase())
                                ) {
                            //обязательное поле не заполнено
                            logger.info("rule:required field " + bodyField + " not filled");
                            continue bodyrules;
                        }
                    }
                    //нашлись поля для этой секции
                    logger.info("rule:fields found:");
                    for (Map.Entry<String, String> entry : record.entrySet()) {
                        logger.info("rule:" + entry.getKey() + "=" + entry.getValue());
                    }

                    this.templateObject.addRecord(record, message);
                    messageProcessed = true;
                    logger.info("rule: record added");
                    continue section;   //эту секцию отработали

                }
            }

            if (!messageProcessed && allbodyfields.isEmpty()) {
                //всё в порядке, нет полей, надо всёравно добавить в шаблон один раз
                    Map<String, String> record = new HashMap<>();
                    record.putAll(subjectrecord);

                    this.templateObject.addRecord(record, message);
                    messageProcessed = true;
                    logger.info("rule: empty body record added");
            }

        } catch (Exception ex) {
            logger.log(Level.SEVERE, "caught exception", ex);
        }

        if (messageProcessed) {
            boolean done = this.templateObject.processRecords();
            if (done) message.getFolder().expunge();   //сбивает нумерацию
        }
        return messageProcessed;
    }

    private static final Logger logger =
            Logger.getLogger(Rule.class.getName());

}
