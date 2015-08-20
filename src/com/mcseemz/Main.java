package com.mcseemz;

import ca.zmatrix.cli.ParseCmd;
import com.google.gson.GsonBuilder;
import com.sun.mail.imap.IdleManager;

import javax.mail.*;
import java.io.*;
import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static java.nio.file.StandardWatchEventKinds.*;

public class Main {

    public static void main(String[] args) throws IOException, MessagingException {
        //https://code.google.com/p/parse-cmd/
        String usage = "usage: [-help] [-nomonitor] [-archive YYYY-MM-DD] [-archiveall] [-flush] [-workdir path_to_data]\n" +
                "options:\n" +
                "   archive    -   processes all messages before selected date, inclusive.\n" +
                "   archiveall    -   processes all messages before selected date, inclusive.\n" +
                "   nomonitor  -   disable folder monitoring for new messages. Alerts will be disabled.\n" +
                "   learn  -   when archive enabled, analizes unprocessed messages and created templates for them.\n" +
                "   workdir    -   path to another folder with data\n";
        ParseCmd cli = new ParseCmd.Builder()
                .help(usage)
                .parm("-nomonitor", "0", "1")
                .parm("-learn", "1")
                .parm("-help", "0","1")
                .parm("-archiveall","0","1")
                .parm("-noflush","0","1")
                .parm("-archive", "0").rex("\\d{4}-\\d{2}-\\d{2}")
                .parm("-workdir", "0").build();

        Map<String,String> R = new HashMap<>();
        String parseError    = cli.validate(args);
        if( cli.isValid(args) ) {
            R = cli.parse(args);
            System.out.println(cli.displayMap(R));
        }
        else { System.out.println(parseError); System.exit(1); }

        // R contains default or input values for defined parms and used as in:
        // long loop = Long.parseLong( R.get("-loop"));
        boolean isNomonitor = R.<String>get("-nomonitor").equals("1");
        isNoflush = R.<String>get("-noflush").equals("1");
        isArchive = !R.<String>get("-archive").equals("0");
        boolean isLearn = !R.<String>get("-learn").equals("0");
        isArchive |= !R.<String>get("-archiveall").equals("0");
        Date datebefore = null;

        System.out.println("isNomonitor:"+isNomonitor);
        System.out.println("isArchive:"+isArchive);

        SimpleDateFormat YMD = new SimpleDateFormat("yyyy-MM-dd");
        if (!R.<String>get("-archive").equals("0")) try {
            datebefore = YMD.parse(R.<String>get("-archive"));
            datebefore.setTime(datebefore.getTime()+3600*24*1000-1);    //до конца суток
        } catch (ParseException e) {
            System.out.println("wrong date format");
            System.exit(-1);
        }

        if (!R.<String>get("-workdir").equals("0")) {
            workdir = R.<String>get("-workdir");
        }
        else workdir = System.getProperty("user.dir")+"/data";

        System.out.println("using workdir:"+workdir);

        //инициализировать ящики
        Mailbox[] mailboxes = initMailboxes();

        //инициализировать шаблоны
        templates = Template.initTemplates();

        //инициализировать правила
        rules = Rule.initRules();
        Map<String, List<Rule>> mappedrules = new HashMap<>();

        Iterator<Rule> iterator = rules.iterator();
        while (iterator.hasNext()) {
            Rule rule = iterator.next();
            if (rule.templateObject==null) {    //еще нет шаблона для правила
                //проверка, что для этого правила есть шаблон
                System.out.println("check template for rule: " + rule.name);
                boolean templateFound = false;
                for (Template testTemplate : templates) {
                    if (testTemplate.name.equals(rule.template)) {
                        templateFound = true;
                        rule.templateObject = testTemplate;
                        break;
                    }
                }
                if (!templateFound) {
                    System.out.println("no template found for " + rule.template);
                    iterator.remove();
                }
                /*todo если шаблонов несколько, то поменять принцип
                выкидывать отсутствующие шаблоны из правила. Если вообще не осталось шаблонов, то выкинуть правило*/
            }
        }

        //сгруппировать правила по ящикам и папкам
        for (Rule rule : rules) {
            String key = rule.getKey();
            if (!mappedrules.containsKey(key))
                mappedrules.put(key, new CopyOnWriteArrayList<Rule>());
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

        System.out.println("rules inited");
        //инициализация idle сессий
        for (Mailbox mailbox : mailboxes) {
            mappedmailboxes.put(mailbox.mailbox, mailbox);
            if (!isNomonitor) {
                mappedIdleSessions.put(mailbox.mailbox, openIdleManagerSession());
                mappedIdleManagers.put(mailbox.mailbox, new IdleManager(mappedIdleSessions.get(mailbox.mailbox), Main.listenerExecutor));
            }
        }

        //filewatcher usable only in monitoring mode
        if (!isNomonitor) {
            watchService = FileSystems.getDefault().newWatchService();

            ftemplate = Paths.get(workdir + "/templates").register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
            Thread fileWatcher = new Thread(new FileWatcher(ftemplate, FileWatcher.MODE_TEMPLATE));
            fileWatcher.start();

            frule = Paths.get(workdir + "/rules").register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
//            Thread fileWatcher2 = new Thread(new FileWatcher(frule, FileWatcher.MODE_RULE));
//            fileWatcher2.start();
        }


        //инициализация обработчиков idle
        if (!isNomonitor)
        for (Map.Entry<String, List<Rule>> entry : mappedrules.entrySet()) {
            try {
                IdleAdapter.addIdleAdapter(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                e.printStackTrace(System.out);
                System.exit(-5);
            }
        }

//        Thread inbox = new Thread(new RPTThread(mappedmailboxes.get("m.zarudnyak@bgoperator.com"), "INBOX", templates, rules));
//        inbox.start();

        if (isArchive) {
            ExecutorService executorService = Executors.newFixedThreadPool(5);
            //запустить основной поток
            for (Map.Entry<String, List<Rule>> entry : mappedrules.entrySet()) {
                Archive process = new Archive(mappedmailboxes.get(entry.getValue().get(0).mailbox), entry.getValue().get(0).folder, entry.getValue(), datebefore, isLearn);
                archiveList.add(executorService.submit(process));
            }

            //проверка что все потоки отработали
//        executorService.
            while (true) {
                boolean allFinished = true;
                for (Future future : archiveList) {
                    if (!future.isDone()) allFinished = false;
                }

                if (allFinished) break;
            }

            executorService.shutdownNow();
        }

//        System.exit(0);
        System.out.println("main thread exits;");
    }

    private static Mailbox[] initMailboxes() throws FileNotFoundException {
        return new GsonBuilder().create().fromJson(new FileReader(workdir+"/mailbox.json"), Mailbox[].class);
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

    private static Session openIdleManagerSession() throws MessagingException {
        Properties props = System.getProperties();
        props.setProperty("mail.store.protocol", "imap");
        props.setProperty("mail.imap.usesocketchannels", "true");

        Session imapsession = Session.getDefaultInstance(props, null);
        imapsession.setDebug(true);
//        for (Provider provider : imapsession.getProviders())
//            System.out.println("we have idle provider:"+provider.getProtocol()+" "+provider.getType()+" "+provider.getClassName());
//        Store store = imapsession.getStore("imap");
//				Store store = imapsession.getStore("imaps");
//        store.connect(mailbox.imap_address, mailbox.imap_port, mailbox.mailbox, mailbox.password);
//        store.getFolder("INBOX");

        System.out.println("opening idleSession done");
        return imapsession;
    }


    public static String workdir;

    /** mapping mailbox name to object*/
    public static final Map<String, Mailbox> mappedmailboxes = new HashMap<>();
    public static final Map<String, IdleManager> mappedIdleManagers = new HashMap<>();
    public static final Map<String, Session> mappedIdleSessions = new ConcurrentHashMap<>();
    public static final Map<String, IdleAdapter> mappedIdleAdapters = new ConcurrentHashMap<>();
    private static List<Future> archiveList = new ArrayList<>();
    /** archive mode turned on */
    public static boolean isArchive = false;
    /** do not send uncomplete report after archiving */
    public static boolean isNoflush = false;

    /**global rules list*/
    public static List<Rule> rules = null;
    /**global templates list */
    public static List<Template> templates = null;

    final static String KEYWORD_SECTION = "#section";
    final static String KEYWORD_DATE = "#mdate";
    final static String KEYWORD_TIME = "#mtime";
    final static String KEYWORD_FROM = "#mfrom";
    final static String KEYWORD_FOLDER = "#mfolder";

    /** Executor service for idleListeners */
    public static final ExecutorService listenerExecutor = Executors.newCachedThreadPool();
    final public static Pattern fieldsPattern = Pattern.compile("<(#?\\w+)>");
    final public static Pattern fieldsFormatPattern = Pattern.compile("<(#?\\w+)(#.?\\d+\\w?)?>");
    static public WatchService watchService = null;
    static public Thread fileWatcher = null;

    static WatchKey ftemplate;
    static WatchKey frule;


}
