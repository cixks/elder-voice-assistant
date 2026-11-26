package cn.family.littlecompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class Notifications {
    static Notification make(Context context, String channelId, String channelName, String text, PendingIntent stop) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW));
        PendingIntent settings = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, channelId)
                .setSmallIcon(cn.family.littlecompanion.R.drawable.ic_companion)
                .setContentTitle("小伴语音助手").setContentText(text)
                .setContentIntent(settings).setOngoing(true).setShowWhen(false);
        if (stop != null) builder.addAction(new Notification.Action.Builder(null, "关闭", stop).build());
        return builder.build();
    }
}
