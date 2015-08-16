package com.mcseemz;

import com.google.gson.stream.JsonReader;

import javax.mail.MessagingException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Alert instanse and initializations
 * Created by mcseem on 16.08.15.
 */
public class Alert {
    /**
     * repetitions for alert
     */
    private int count;
    /**
     * time span for alert
     */
    private int seconds;
    /**
     * mailbox to send alert from
     */
    private String mailbox;
    /**
     * email to send alert to
     */
    private String to;
    /**
     * alert email subject
     */
    private String subject;
    /**
     * alert name
     */
    private String name;
    /**
     * reset alert on date change
     */
    private boolean isToday = false;

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
        return alert;
    }

    public void addRecord(Map<String,String> record) {
        Iterator<Date> iterator = list.iterator();
        Date now = new Date();
        //выкинуть из списка все устаревшие
        String formattedDate = dateFormat.format(now);
        while (iterator.hasNext()) {
            Date next = iterator.next();
            if (now.getTime()-next.getTime()/1000>seconds) iterator.remove();
            else if (isToday && !formattedDate.equals(dateFormat.format(next))) iterator.remove();
        }
        boolean alertSent = false;
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

        try {
            Template.sendEmail(Main.mappedmailboxes.get(mailbox), to, "ALERT: "+subject, sb, "text/plain; charset=UTF-8");
        } catch (MessagingException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");
}
