package com.livenotification.notification.application;

public record RegisterResult(NotificationDetail detail, boolean eventDuplicate, boolean replay) {}
