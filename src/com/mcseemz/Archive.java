package com.mcseemz;

import javax.mail.*;
import javax.mail.search.FlagTerm;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Created by mcseem on 24.07.15.
 * process thread
 */
public class Archive implements RunnableFuture{

    private Main.Mailbox mailbox;
    private String folder;
    private List<Template> templates;
    private List<Rule> rules;
    private String threadName = "default";
    private boolean shouldCancel = false;
    private boolean isCancelled = false;
    private Date datebefore = null;
    private boolean learnmode;
    Session imapsession = null;
    Store store = null;

    public Archive(Main.Mailbox mailbox, String folder, List<Rule> rules, Date datebefore, boolean learnmode) {
        this.mailbox = mailbox;
        this.folder = folder;
        this.templates = templates;
        this.rules = rules;
        this.datebefore = datebefore;
        this.learnmode = learnmode;
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

            System.out.println("going search");
            FlagTerm ft = new FlagTerm(new Flags(Flags.Flag.DELETED), false);
//                SearchTerm st = new SubjectTerm(rule.subject);
//                AndTerm andTerm = new AndTerm(ft,st);
//                Message[] messages = folder.search(andTerm);
            Message[] messages = folder.search(ft);
            System.out.println("for folder " + folder + " found total messages:" + messages.length);

            for (Message message : messages) {
                boolean isprocessed = false;
                for (Rule rule : rules) {
                    if (shouldCancel) {
                        isCancelled = true;
                        System.out.println("should cancel detected");
                        break;
                    }
                    if (datebefore!=null && message.getSentDate().compareTo(datebefore)>0) continue;    //ограничение по датам

                    try {
                        isprocessed |= rule.processMessage(message);
                    } catch (javax.mail.MessageRemovedException ex) {
                        System.out.println("message removed already!");
                    }
                }

                //если режим обучения
                if (!isprocessed && learnmode) {
                    // простой алгоритм
                    // выкинуть из строки цифры.
                    // если строка пустая или короткая, то дальше
                    // тема и отправитель будут ключом.
                    // если количество совпавших больше одного, то создать шаблон
                }
            }
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
