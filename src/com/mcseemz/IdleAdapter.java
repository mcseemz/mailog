package com.mcseemz;

import com.sun.mail.imap.IdleManager;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import java.util.List;

/**
 * Created by mcseem on 04.08.15.
 */
public class IdleAdapter extends MessageCountAdapter {
    private IdleManager idleManager;
    private final Session imapsession;
    private List<Main.Rule> rules;

    public IdleAdapter(IdleManager idleManager, Session imapsession, List<Main.Rule> rules) {

        this.idleManager = idleManager;
        this.imapsession = imapsession;
        this.rules = rules;
    }

    public void messagesAdded(MessageCountEvent ev) {
        Folder folder = (Folder) ev.getSource();
        Message[] msgs = ev.getMessages();
        System.out.println("Folder: " + folder +
                " got " + msgs.length + " new messages");

        for (Message message : msgs) {
            for (Main.Rule rule : rules) {
                try {
                    synchronized (imapsession) {
                        Process.processMessage(rule, message);
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
