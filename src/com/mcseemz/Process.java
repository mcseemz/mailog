package com.mcseemz;

import javax.mail.*;
import javax.mail.search.AndTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by mcseem on 24.07.15.
 * process thread
 */
public class Process implements RunnableFuture{

    private Main.Mailbox mailbox;
    private String folder;
    private List<Main.Template> templates;
    private List<Main.Rule> rules;
    private String threadName = "default";
    private boolean shouldCancel = false;
    private boolean isCancelled = false;
    Session imapsession = null;
    Store store = null;

    public Process(Main.Mailbox mailbox, String folder, List<Main.Template> templates, List<Main.Rule> rules) {
        this.mailbox = mailbox;
        this.folder = folder;
        this.templates = templates;
        this.rules = rules;
    }

    @Override
    public void run() {
        //открыть ящик
        threadName = mailbox.mailbox+"/"+folder;
        Thread.currentThread().setName(threadName);

        Folder folder = null;
        try {
            folder = openSession(mailbox, this.folder);

            System.out.println("folder opened");
            System.out.println("rules.size "+rules.size());
            //идем по правилам
            for (Main.Rule rule : rules) {
                if (shouldCancel) {
                    isCancelled = true;
                    System.out.println("should cancel detected");
                    break;
                }


                System.out.println("going search");
                FlagTerm ft = new FlagTerm(new Flags(Flags.Flag.DELETED), false);
//                SearchTerm st = new SubjectTerm(rule.subject);
//                AndTerm andTerm = new AndTerm(ft,st);
//                Message[] messages = folder.search(andTerm);
                Message[] messages = folder.search(ft);
                System.out.println("for rule " + rule.name + " found total messages:" + messages.length);


                //todo развернуть правила и сообщения, чтобы по сообщению проходились все правила

                for (Message message : messages) {
                    if (processMessage(rule, message)) continue;
                }

            }

//            FetchProfile fp = new FetchProfile();
//            fp.add(FetchProfile.Item.ENVELOPE);
//            fp.add("X-mailer");
//            inbox.fetch(messages, fp);


        } catch (Exception e) {
            e.printStackTrace(System.out);

        } finally {
            try {
                if (folder!=null) {
                    folder.expunge();
                    folder.close(true);
                }
                closeSession();
                System.out.println(Thread.currentThread().getName()+" done");
                isCancelled = true;
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        }
    }

    public static boolean processMessage(Main.Rule rule, Message message) throws MessagingException {
        String subject = message.getSubject();
        if (subject.startsWith("RPT:")) {
            System.out.println("report message detected. Skip");
            return true;
        }

        boolean messageProcessed = false;

        try {
            Pattern psubject = Pattern.compile(rule.subject);
            Matcher subjectMatcher = psubject.matcher(subject);
            if (!subjectMatcher.find()) return true;

            System.out.println("subject:"+subject);

            //разбить тело на секции
            //для каждой секции формируется свое множество полей
            //в это множество попадает и разобранный subject, и метаполя
            //проверка на флаги для полей

            String contentType = message.getContentType();
            StringBuilder text = new StringBuilder();

            System.out.println("contentType:"+contentType);


            if (contentType.contains("multipart")) {
                Multipart multipart = (Multipart) message.getContent();
                for (int j = 0; j < multipart.getCount(); j++) {
                    BodyPart bodyPart = multipart.getBodyPart(j);
                    String bodyPartContentType = bodyPart.getContentType();

                    System.out.println("bodyPartContentType:"+contentType);

                    if (bodyPartContentType.contains("text/plain")) {
                        text.append(bodyPart.getContent());
                    }
                    else if (bodyPartContentType.contains("text/html")) {
                        text.append(bodyPart.getContent());
                    }
                    else if (bodyPartContentType.contains("multipart")) {
                        multipart = (Multipart) bodyPart.getContent();

                        for (int k = 0; k < multipart.getCount(); k++) {
                            bodyPart = multipart.getBodyPart(k);
                            bodyPartContentType = bodyPart.getContentType();

                            System.out.println("bodyPartContentType:"+contentType);

                            if (bodyPartContentType.contains("text/plain")) {
                                text.append(bodyPart.getContent());
                            }
                            else if (bodyPartContentType.contains("text/html")) {
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

            //собрать поля из темы в список
            Matcher fieldMatcher = fieldsPattern.matcher(rule.subject);
            List<String> subjectFields = new ArrayList<>();
            while (fieldMatcher.find()) subjectFields.add(fieldMatcher.group(1));

            System.out.println("subjectFields found:"+subjectFields);

            //разбиваем по секциям
            //todo бить по всем опмсателям секций, а не только по первому
            String[] sections = rule.section.isEmpty()
                    ? new String[]{text.toString()}
                    : text.toString().split(rule.section.get(0));

            System.out.println("sections size:"+sections.length);

section:                for (String section : sections) {
                System.out.println("section:"+ section);

                fieldMatcher.reset();
                Map<String, String> record = new HashMap<>();

                //кладем поля и темы в запись
                subjectMatcher.reset();
                subjectMatcher.find();
                for (int i = 0; i < subjectMatcher.groupCount(); i++) {
                    record.put(subjectFields.get(i), subjectMatcher.group(i + 1));
                    record.put(subjectFields.get(i)+"_flags", "s"); //из subject
                }

                //теперь поля из тела
                for (String bodyrule : rule.body) {
                    Matcher bodyMatcher = fieldsPattern.matcher(bodyrule);
                    List<String> bodyFields = new ArrayList<>();
                    while (bodyMatcher.find()) bodyFields.add(bodyMatcher.group(1));

                    System.out.println("bodyFields found:"+bodyFields);

                    Pattern pbody = Pattern.compile(bodyrule);
                    Matcher mbody = pbody.matcher(section);
                    if (mbody.find())
                        for (int i = 0; i < mbody.groupCount(); i++) {
                            record.put(bodyFields.get(i).toLowerCase(), mbody.group(i + 1));
                            record.put(bodyFields.get(i).toLowerCase()+"_flags", "b");
                        }
                    else System.out.println("not found");

                    //проверка
                    for (String bodyField : bodyFields) {
                        if (bodyField.toUpperCase().equals(bodyField)
                                && !record.containsKey(bodyField.toLowerCase())
                                ) {
                            System.out.println("required field "+bodyField+" not filled");
                            continue section; //обязательное поле не заполнено
                        }
                    }
                }

                System.out.println("record done:");
                for (Map.Entry<String, String> entry : record.entrySet()) {
                    System.out.println(entry.getKey()+"="+entry.getValue());
                }

                //record заполнено
                //проверяем на флаги полей
//                            for (Map.Entry<String, String> entryFlag : rule.flags.entrySet()) {
//                                boolean isRequired = entryFlag.getValue().contains("r");
//                                if ((!record.containsKey(entryFlag.getKey())
//                                        || record.get(entryFlag.getKey()).isEmpty())
//                                        && isRequired) continue section;    //плохо собрали
//                            }


                rule.templateObject.addRecord(record, message);
                messageProcessed = true;
                System.out.println("rule: record added");
            }

        } catch (Exception ex) {
            ex.printStackTrace(System.out);
        }

        if (messageProcessed) {
            boolean done = rule.templateObject.processRecords();
            if (done) message.getFolder().expunge();   //сбивает нумерацию
        }
        return false;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        shouldCancel = true;
        return shouldCancel;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled;
    }

    @Override
    public boolean isDone() {
        return isCancelled;
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
        return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return null;
    }


    private Folder openSession(Main.Mailbox mailbox, String folder) throws MessagingException {
        Properties props = System.getProperties();
        props.setProperty("mail.store.protocol", "imap");

        imapsession = Session.getDefaultInstance(props, null);
        imapsession.setDebug(true);
        for (Provider provider : imapsession.getProviders())
            System.out.println("we have provider:"+provider.getProtocol()+" "+provider.getType()+" "+provider.getClassName());
        store = imapsession.getStore("imap");
//				Store store = imapsession.getStore("imaps");
        store.connect(mailbox.imap_address, mailbox.imap_port, mailbox.mailbox, mailbox.password);
        System.out.println(store);

        System.out.println("getting folder");
        Folder fld = store.getFolder(folder);
        System.out.println("done");
        fld.open(Folder.READ_WRITE);
        System.out.println("opening done");
        return fld;
    }


    private boolean closeSession() throws MessagingException {
        if (store!=null) store.close();

        return true;
    }


    final public static Pattern fieldsPattern = Pattern.compile("\\(\\?<(\\w+)>");

}
