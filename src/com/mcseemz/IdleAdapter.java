package com.mcseemz;

import com.sun.mail.imap.IdleManager;

import javax.mail.*;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import java.util.List;

/**
 * Created by mcseem on 04.08.15.
 */
public class IdleAdapter extends MessageCountAdapter {
    private IdleManager idleManager;
    private final Session imapsession;
    private List<Rule> rules;

    public IdleAdapter(IdleManager idleManager, Session imapsession, List<Rule> rules) {

        this.idleManager = idleManager;
        this.imapsession = imapsession;
        this.rules = rules;
    }

    public static void addIdleAdapter(String key, List<Rule> rules) throws MessagingException {
        Folder folder;Session session = Main.mappedIdleSessions.get(rules.get(0).mailbox);
        Store store = session.getStore("imap");
        if (!store.isConnected()) {
            Main.Mailbox mailbox = Main.mappedmailboxes.get(rules.get(0).mailbox);
            store.connect(mailbox.imap_address, mailbox.imap_port, mailbox.mailbox, mailbox.password);
        }
        folder = store.getFolder(rules.get(0).folder);
        folder.open(Folder.READ_WRITE);

        IdleManager idleManager = Main.mappedIdleManagers.get(rules.get(0).mailbox);

        IdleAdapter idleAdapter = new IdleAdapter(idleManager, session, rules);
        Main.mappedIdleAdapters.put(key, idleAdapter);    //сохраняем, чтобы можно было релоадить правила

        folder.addMessageCountListener(idleAdapter);
        idleManager.watch(folder);
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void messagesAdded(MessageCountEvent ev) {
        Folder folder = (Folder) ev.getSource();
        Message[] msgs = ev.getMessages();
        System.out.println("Folder: " + folder +
                " got " + msgs.length + " new messages");

        for (Message message : msgs) {
            for (Rule rule : rules) {
                try {
                    synchronized (imapsession) {
                        try {
                            rule.processMessage(message);
                        } catch (javax.mail.MessageRemovedException ex) {
                            System.out.println("message removed already!");
                            break;
                        }
                    }
                } catch (MessagingException e) {
                    e.printStackTrace();
                }
            }
        }

        try {
            // process new messages
            idleManager.watch(folder); // keep watching for new messages
        } catch (MessagingException mex) {
            mex.printStackTrace(System.out);
            // handle exception related to the Folder
        }
    }

}
