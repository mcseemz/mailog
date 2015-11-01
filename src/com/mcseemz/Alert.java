package com.mcseemz;

import com.google.gson.stream.JsonReader;

import javax.mail.MessagingException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Alert instance and initializations
 * Created by mcseem on 16.08.15.
 */
public class Alert {
    /**
     * repetitions for alert
     */
    public int count;
    /**
     * time span for alert
     */
    public int seconds;
    /**
     * mailbox to send alert from
     */
    public String mailbox;
    /**
     * email to send alert to
     */
    public String to;
    /**
     * alert email subject
     */
    public String subject;
    /**
     * alert name
     */
    public String name;
    /**
     * reset alert on date change
     */
    public boolean isToday = false;

    LinkedBlockingQueue<Date> list = new LinkedBlockingQueue<>(1);

    private Alert() {
    }

    public static Alert initAlert(JsonReader reader) throws IOException {
        Alert alert = new Alert();
        reader.beginObject();
        while (reader.hasNext()) {
            String tokenname = reader.nextName();
            if (tokenname.equals("name")) alert.name = reader.nextString();
            if (tokenname.equals("count")) {
                alert.count = reader.nextInt();
                alert.list = new LinkedBlockingQueue<>(alert.count);
            }
            if (tokenname.equals("frequency")) {
                String freq = reader.nextString();
                int val = Integer.parseInt(freq.replaceAll("\\D", ""));
                if (freq.replaceAll("\\d", "").equalsIgnoreCase("m")) val *= 60;
                else if (freq.replaceAll("\\d", "").equalsIgnoreCase("h")) val *= 3600;
                else if (freq.replaceAll("\\d", "").equalsIgnoreCase("d")) val *= 3600 * 24;
                else if (freq.replaceAll("\\d", "").equalsIgnoreCase("today")) {
                    val *= 3600 * 24;
                    alert.isToday = true;
                }
                alert.seconds = val;
            }
            if (tokenname.equals("subject")) alert.subject = reader.nextString();
            if (tokenname.equals("to")) alert.to = reader.nextString();
            if (tokenname.equals("mailbox")) alert.mailbox = reader.nextString();
        }
        reader.endObject();

        if (alert.name==null) alert.name = String.valueOf(Math.random());
        return alert;
    }

    public void addRecord(Map<String,String> record) {
        Iterator<Date> iterator = list.iterator();
        Date now = new Date();
        //выкинуть из списка все устаревшие
        String formattedDate = dateFormat.format(now);
        while (iterator.hasNext()) {
            Date next = iterator.next();
            if ((now.getTime()-next.getTime())/1000>seconds) iterator.remove();
            else if (isToday && !formattedDate.equals(dateFormat.format(next))) iterator.remove();
        }
        boolean alertSent = false;

        logger.info("alert. remains:"+list.remainingCapacity());
        //если нельзя добавить новый, то алерт, сбросить
        if (list.remainingCapacity()==0) {
            alertSent = sendAlert(record);
            list.clear();
        }
        //добавить новый
        //если исключение из-за заполнения, то алерт, сбросить
        if (!list.offer(now)) {
            if (!alertSent) alertSent = sendAlert(record);
            list.clear();
        }

        //если нельзя добавить новый, то алерт, сбросить
        if (list.remainingCapacity()==0) {
            if (!alertSent) sendAlert(record);
            list.clear();
        }
    }

    private boolean sendAlert(Map<String,String> record) {
        StringBuilder sb = new StringBuilder(
                "всего записей:"+list.size()+"\n"
                +"срок, сек:"+seconds+"\n"
                +"первая:"+list.peek()+"\n"
                +"Последняя запись:\n");
        for (Map.Entry<String, String> entry : record.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }

        logger.info("sending alert.");
        try {
            Template.sendEmail(Main.mappedmailboxes.get(mailbox), to, "ALERT: "+subject, sb, "text/plain; charset=UTF-8");
        } catch (MessagingException e) {
            e.printStackTrace();
            return false;
        }
        logger.info("sending alert done");

        return true;
    }

    SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

    private static final Logger logger =
            Logger.getLogger(Alert.class.getName());
}
