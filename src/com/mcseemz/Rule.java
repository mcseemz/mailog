package com.mcseemz;

import com.google.gson.stream.JsonReader;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by mcseem on 08.08.15.
 */
public class Rule {
    String name;
    String mailbox;
    String folder;
    String subject = "";
    String from = "";
    String to = "";
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
        final List<Rule> rules = new ArrayList<>();

        Files.walkFileTree(Paths.get(Main.workdir + "/rules"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                System.out.println("processing rule file: " + file.getFileName());

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
                rules.add(rule);
                return FileVisitResult.CONTINUE;
            }

        });
        return rules;
    }

    public boolean processMessage(Message message) throws MessagingException {
        String subject = message.getSubject();
        if (subject.startsWith("RPT:")) {
            System.out.println("report message detected. Skip");
            return false;
        }

        System.out.println("now rule:" + this.name);

        boolean messageProcessed = false;

        try {
            Pattern psubject = Pattern.compile(this.subject);
            Matcher subjectMatcher = psubject.matcher(subject);
            if (!subjectMatcher.find()) {
                System.out.println("subject failed:" + subject);
                return false;
            }

            System.out.println("subject:" + subject);

            //todo проверка на другие поля из шапки
//            if (!this.from.isEmpty())
//            if (!this.to.isEmpty())
            if (!this.age.isEmpty()) {
                Date sentDate = message.getSentDate();
                Date now = new Date();
                int val = Integer.valueOf(this.age.replaceAll("\\D", ""));

                int tz1 = TimeZone.getDefault().getOffset(sentDate.getTime());
                int tz2 = TimeZone.getDefault().getOffset(now.getTime());
                long diff = 0;
                if (this.age.endsWith("D")) {
                    diff = Math.abs(now.getTime() - sentDate.getTime() + (tz2 - tz1)) / 86400 / 1000 + 1;
                }
                if (this.age.endsWith("H")) {
                    diff = Math.abs(now.getTime() - sentDate.getTime() + (tz2 - tz1)) / 3600 / 1000 + 1;
                }

                if (this.age.contains(">") && diff > val) ;
                else if (this.age.contains(">=") && diff >= val) ;
                else if (this.age.contains("<") && diff < val) ;
                else if (this.age.contains("<=") && diff <= val) ;
                else {
                    System.out.println("age failed:" + this.age + "/" + diff);
                    return false;
                }
            }

            //разбить тело на секции
            //для каждой секции формируется свое множество полей
            //в это множество попадает и разобранный subject, и метаполя
            //проверка на флаги для полей

            String contentType = message.getContentType();
            StringBuilder text = new StringBuilder();

            System.out.println("contentType:" + contentType);


            if (contentType.contains("multipart")) {
                Multipart multipart = (Multipart) message.getContent();
                for (int j = 0; j < multipart.getCount(); j++) {
                    BodyPart bodyPart = multipart.getBodyPart(j);
                    String bodyPartContentType = bodyPart.getContentType();

                    System.out.println("bodyPartContentType:" + contentType);

                    if (bodyPartContentType.contains("text/plain")) {
                        text.append(bodyPart.getContent());
                    } else if (bodyPartContentType.contains("text/html")) {
                        text.append(bodyPart.getContent());
                    } else if (bodyPartContentType.contains("multipart")) {
                        multipart = (Multipart) bodyPart.getContent();

                        for (int k = 0; k < multipart.getCount(); k++) {
                            bodyPart = multipart.getBodyPart(k);
                            bodyPartContentType = bodyPart.getContentType();

                            System.out.println("bodyPartContentType:" + contentType);

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
            while (fieldMatcher.find()) subjectFields.add(fieldMatcher.group(1));


            System.out.println("subjectFields found:" + subjectFields);

            //разбиваем по секциям
            //todo бить по всем опмсателям секций, а не только по первому
            String[] sections = this.sectionsplitter.isEmpty()
                    ? new String[]{text.toString()}
                    : text.toString().split(this.sectionsplitter.get(0));

            System.out.println("rule:sections size:" + sections.length);

            Map<String, String> subjectrecord = new HashMap<>();
            //кладем поля и темы в запись
            subjectMatcher.reset();
            subjectMatcher.find();
            for (int i = 0; i < subjectMatcher.groupCount(); i++) {
                subjectrecord.put(subjectFields.get(i), subjectMatcher.group(i + 1));
                subjectrecord.put(subjectFields.get(i) + "_flags", "s"); //из subject
            }

            section:
            for (String section : sections) {
                System.out.println("rule: section:" + section);

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
                            bodyFields.add(bodyMatcher.group(1));
                            allbodyfields.add(bodyMatcher.group(1));
                        }

                        System.out.println("rule:bodyFields found:" + bodyFields);

                        Pattern pbody = Pattern.compile(bodyrule);
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
                                    record.put(bodyFields.get(i).toLowerCase(), record.get(bodyFields.get(i).toLowerCase()) + " | " + mbody.group(i + 1));
                                } else record.put(bodyFields.get(i).toLowerCase(), mbody.group(i + 1));
                                record.put(bodyFields.get(i).toLowerCase() + "_flags", "b");
                            }
                        }
                        if (!isFound) System.out.println("rule:not found");

                        bodyrulefields.addAll(bodyFields);
                    }

                    //проверка
                    for (String bodyField : bodyrulefields) {
                        if (bodyField.toUpperCase().equals(bodyField)
                                && !record.containsKey(bodyField.toLowerCase())
                                ) {
                            //обязательное поле не заполнено
                            System.out.println("rule:required field " + bodyField + " not filled");
                            continue bodyrules;
                        }
                    }
                    //нашлись поля для этой секции
                    System.out.println("rule:fields found:");
                    for (Map.Entry<String, String> entry : record.entrySet()) {
                        System.out.println("rule:"+entry.getKey() + "=" + entry.getValue());
                    }

                    this.templateObject.addRecord(record, message);
                    messageProcessed = true;
                    System.out.println("rule: record added");
                    continue section;   //эту секцию отработали

                }
            }

            if (!messageProcessed && allbodyfields.isEmpty()) {
                //всё в порядке, нет полей, надо всёравно добавить в шаблон один раз
                    Map<String, String> record = new HashMap<>();
                    record.putAll(subjectrecord);

                    this.templateObject.addRecord(record, message);
                    messageProcessed = true;
                    System.out.println("rule: empty body record added");
            }

        } catch (Exception ex) {
            ex.printStackTrace(System.out);
        }

        if (messageProcessed) {
            boolean done = this.templateObject.processRecords();
            if (done) message.getFolder().expunge();   //сбивает нумерацию
        }
        return messageProcessed;
    }

}
