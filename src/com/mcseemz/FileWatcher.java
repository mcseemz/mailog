package com.mcseemz;

import com.google.gson.stream.JsonReader;

import javax.mail.MessagingException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Created by mcseem on 19.08.15.
 */
public class FileWatcher implements Runnable{
    private WatchKey watchKey;
    private int mode;

    public FileWatcher(WatchKey watchKey, int mode) {
        this.watchKey = watchKey;
        this.mode = mode;
    }
    @Override
    public void run() {
        while (true) {
            // wait for key to be signaled
            WatchKey key;
            try {
                key = Main.watchService.take();
            } catch (InterruptedException x) {
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                // This key is registered only
                // for ENTRY_CREATE events,
                // but an OVERFLOW event can
                // occur regardless if events
                // are lost or discarded.
                if (kind == OVERFLOW) {
                    continue;
                }

                // The filename is the
                // context of the event.
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                if (key.equals(Main.ftemplate)) {
                    logger.info("template change detected:" + filename.toAbsolutePath().toString());
                    InputStreamReader ireader;
                    try {
                        ireader = new InputStreamReader(Files.newInputStream(Paths.get(Main.workdir+"/templates/"+filename.getFileName())));
                        JsonReader reader = new JsonReader(ireader);

                        Template newTemplate = Template.parseTemplate(reader);

                        boolean processed = false;
                        //если это template, то смотреть список шаблонов
                        for (Template oldTemplate : Main.templates)
                            if (oldTemplate.name.equals(newTemplate.name)
                                && !oldTemplate.version.equals(newTemplate.version)) {
                                //отличающиеся шаблоны
                                updateTemplate(newTemplate, oldTemplate);

                                processed = true;
                                logger.info("template " + oldTemplate.name + " v." + oldTemplate.version + " reloaded");
                            }
                            else if (oldTemplate.name.equals(newTemplate.name)
                                    && oldTemplate.version.equals(newTemplate.version)) {
                                logger.info("template " + oldTemplate.name + " v." + oldTemplate.version + " same, not reloaded");
                                processed = true;
                            }

                        if (!processed) {
                            Main.templates.add(newTemplate);
                            logger.info("template " + newTemplate.name + " v." + newTemplate.version + " uploaded");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                else if (key.equals(Main.frule)) {
                    //если это правило, то смотреть единый список правил
                    //rules - обновить rules и mappedrules
                    logger.info("rule change detected:" + filename.toAbsolutePath().toString());
                    InputStreamReader ireader;
                    try {
                        ireader = new InputStreamReader(Files.newInputStream(Paths.get(Main.workdir+"/rules/"+filename.getFileName())));
                        JsonReader reader = new JsonReader(ireader);

                        Rule newRule = Rule.parseRule(reader);

                        boolean processed = false;
                        for (Rule oldRule : Main.rules)
                            if (oldRule.name.equals(newRule.name)
                                    && !oldRule.version.equals(newRule.version)) {
                                if (!oldRule.mailbox.equals(newRule.mailbox)) {
                                    logger.info("cannot change mailbox!! upload " + newRule.name + " v" + newRule.version + " failed");
                                    continue;
                                }
                                if (!oldRule.folder.equals(newRule.folder)) {
                                    logger.info("cannot change folder!! upload " + newRule.name + " v" + newRule.version + " failed");
                                    continue;
                                }

                                    //отличающиеся правила
                                //заблокировать, скопировать данные
                                synchronized (oldRule.block) {
                                    oldRule.name = newRule.name;
                                    oldRule.version = newRule.version;
                                    //todo переносить в другую папку можно? как уведомлять потоки listener'ов?


                                    oldRule.mailbox = newRule.mailbox;
                                    oldRule.folder = newRule.folder;
                                    oldRule.subject = newRule.subject;
                                    oldRule.from = newRule.from;
                                    oldRule.to = newRule.to;
                                    oldRule.age = newRule.age;
                                    oldRule.body = newRule.body;
                                    oldRule.sectionsplitter = newRule.sectionsplitter;
                                    oldRule.flags = newRule.flags;

                                    //если новый объект есть с списке шаблонов, то заменить templateObject на него
                                    //иначе прогнать сравнение и обновление шаблонов
                                    boolean templateFound = false;
                                    for (Template template1 : Main.templates)
                                        if (newRule.templateObject.name.equals(template1.name)) {
                                            oldRule.templateObject = template1;
                                            templateFound = true;
                                            break;
                                        }
                                    if (!templateFound) {
                                        updateTemplate(newRule.templateObject, oldRule.templateObject);
                                    }

                                }
                                processed = true;
                                logger.info("rule " + oldRule.name + " v." + oldRule.version + " reloaded");
                                //при обновлении ничего не нужно
                            }
                            else if (oldRule.name.equals(newRule.name)
                                    && oldRule.version.equals(newRule.version)) {
                                logger.info("rule " + oldRule.name + " v." + oldRule.version + " same, not reloaded");
                                processed = true;
                            }

                        if (!processed) {
                            Main.rules.add(newRule);
                            logger.info("rule " + newRule.name + " v." + newRule.version + " uploaded");
                            IdleAdapter idleAdapter = Main.mappedIdleAdapters.get(newRule.getKey());
                            if (idleAdapter==null) {    //такой папки/mailbox еще не было
                                //создание нового
                                List<Rule> rules = new CopyOnWriteArrayList<>();
                                rules.add(newRule);
                                IdleAdapter.addIdleAdapter(newRule.getKey(), rules);
                            }
                            else {
                                Main.rules.add(newRule);
                                List<Rule> rules = Main.mappedIdleAdapters.get(newRule.getKey()).getRules();
                                rules.add(newRule);
                            }

                        }
                    } catch (IOException | MessagingException e) {
                        e.printStackTrace();
                    }
                }
                key.reset();
            }
        }
    }

    private void updateTemplate(Template newTemplate, Template oldTemplate) {
        //заблокировать, скопировать данные
        synchronized (oldTemplate.block) {
            oldTemplate.name = newTemplate.name;
            oldTemplate.version = newTemplate.version;
//                                    int report_records;
            oldTemplate.report_subject = newTemplate.report_subject;
            oldTemplate.report_to = newTemplate.report_to;
            oldTemplate.report_mailbox = newTemplate.report_mailbox;

            oldTemplate.toSend = newTemplate.toSend;
            oldTemplate.toDelete = newTemplate.toDelete;
            oldTemplate.toUnseen = newTemplate.toUnseen;

            for (int i = 0; i < oldTemplate.sections.size(); i++) {
                if (newTemplate.sections.size()<i) continue;
                Template.Section oldSection = oldTemplate.sections.get(i);
                Template.Section newSection = newTemplate.sections.get(i);

                oldSection.format = newSection.format;
//                                        StringBuilder report = new StringBuilder();
                oldSection.usedFields = newSection.usedFields;
                oldSection.oneTime = newSection.oneTime;
            }
            //корректировка размера секций
            if (newTemplate.sections.size()>oldTemplate.sections.size()) {
                for (int i = oldTemplate.sections.size(); i < newTemplate.sections.size()-1; i++)
                    oldTemplate.sections.add(newTemplate.sections.get(i));
            }
            else if (newTemplate.sections.size()<oldTemplate.sections.size()) {
                for (int i = oldTemplate.sections.size()-1; i >= newTemplate.sections.size()-1; i--)
                    oldTemplate.sections.remove(i);
            }


            for (Alert newAlert : newTemplate.alerts) {
                boolean alertFound = false;
                for (Alert oldAlert : oldTemplate.alerts) {
                    if (oldAlert.name.equals(newAlert.name)) {
                        alertFound = true;
                        oldAlert.count = newAlert.count;

                        List<Date> dates = new ArrayList<>();
                        oldAlert.list.drainTo(dates);   //вылить всё в список
                        oldAlert.list = new LinkedBlockingQueue<>(oldAlert.count);

                        for (Date date : dates)
                            oldAlert.list.offer(date);  //если подрезали, то не страшно, будет заполненный список

                        oldAlert.seconds = newAlert.seconds;
                        oldAlert.mailbox = newAlert.mailbox;
                        oldAlert.to = newAlert.to;
                        oldAlert.subject = newAlert.subject;
                        oldAlert.isToday = newAlert.isToday;
                    }
                }
                if (!alertFound) oldTemplate.alerts.add(newAlert);
            }

            Iterator<Alert> iterator = oldTemplate.alerts.iterator();
            while (iterator.hasNext()) {
                Alert oldAlert = iterator.next();
                boolean alertFound = false;
                for (Alert newAlert : newTemplate.alerts)
                    if (oldAlert.name.equals(newAlert.name))
                        alertFound = true;
                if (!alertFound) iterator.remove();
            }
        }
    }

    public static int MODE_RULE = 0;
    public static int MODE_TEMPLATE = 1;

    private static final Logger logger =
            Logger.getLogger(FileWatcher.class.getName());

}
