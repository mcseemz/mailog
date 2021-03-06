package com.mcseemz;

import javax.mail.*;
import javax.mail.search.FlagTerm;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by mcseem on 24.07.15.
 * process thread
 */
public class Archive implements RunnableFuture{

    private Main.Mailbox mailbox;
    private String folder;
    private List<Rule> rules;
    private boolean shouldCancel = false;
    private boolean isCancelled = false;
    private Date datebefore = null;
    private boolean learnmode;
    Session imapsession = null;
    Store store = null;

    public Archive(Main.Mailbox mailbox, String folder, List<Rule> rules, Date datebefore, boolean learnmode) {
        this.mailbox = mailbox;
        this.folder = folder;
        this.rules = rules;
        this.datebefore = datebefore;
        this.learnmode = learnmode;
    }

    @Override
    public void run() {
        //открыть ящик
        String threadName = mailbox.mailbox + "/" + folder;
        Thread.currentThread().setName(threadName);

        Folder folder = null;
        try {
            folder = openSession(mailbox, this.folder);

            logger.info("folder opened");
            logger.info("rules.size " + rules.size());
            //идем по правилам

            logger.info("going search");
            FlagTerm ft = new FlagTerm(new Flags(Flags.Flag.DELETED), false);
//                SearchTerm st = new SubjectTerm(rule.subject);
//                AndTerm andTerm = new AndTerm(ft,st);
//                Message[] messages = folder.search(andTerm);
            Message[] messages = folder.search(ft);
            logger.info("for folder " + folder + " found total messages:" + messages.length);

messages:   for (Message message : messages) {
                boolean isprocessed = false;
                for (Rule rule : rules) {
                    if (shouldCancel) {
                        isCancelled = true;
                        logger.info("should cancel detected");
                        break;
                    }
                    if (datebefore!=null && message.getSentDate().compareTo(datebefore)>0) continue;    //ограничение по датам

                    try {
                        isprocessed |= rule.processMessage(message);
                    } catch (javax.mail.MessageRemovedException ex) {
                        logger.info("message removed already!");
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

            if (!Main.isNoflush)    //if we flush
            for (Rule rule : rules) {
                rule.templateObject.flush();    //send letters
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "caught exception", e);
        } finally {
            try {
                if (folder!=null) {
                    folder.expunge();
                    folder.close(true);
                }
                closeSession();
                logger.info(Thread.currentThread().getName() + " done");
                isCancelled = true;
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        }
    }


    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        shouldCancel = true;
        return true;
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
        imapsession.setDebug(false);
        for (Provider provider : imapsession.getProviders())
            logger.info("we have provider:" + provider.getProtocol() + " " + provider.getType() + " " + provider.getClassName());
        store = imapsession.getStore("imap");
//				Store store = imapsession.getStore("imaps");
        store.connect(mailbox.imap_address, mailbox.imap_port, mailbox.mailbox, mailbox.password);
        logger.info(store.toString());

        logger.info("getting folder");
        Folder fld = store.getFolder(folder);
        logger.info("done");
        fld.open(Folder.READ_WRITE);
        logger.info("opening done");
        return fld;
    }


    private boolean closeSession() throws MessagingException {
        if (store!=null) store.close();

        return true;
    }

    private static final Logger logger =
            Logger.getLogger(Archive.class.getName());

}
