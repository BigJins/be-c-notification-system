package com.livenotification.notification.application;

import com.livenotification.delivery.domain.Delivery;
import com.livenotification.notification.domain.Notification;

import java.util.List;

public record NotificationDetail(Notification notification, List<Delivery> deliveries) {}
