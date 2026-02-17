package com.irina.vkbot;

class User {
  int userId;
  long firstSeen;
  long lastSeen;
  boolean isSubscribed;
  boolean isAdmin;
  String pendingRef;
}

class Magnet {
  int id;
  String title;
  String description;
  String type;
  String attachment;
  String url;
  String refCode;
  boolean isActive;
}

class AdminState {
  int userId;
  String state;
  String data;
}

class Stats {
  int startsTotal;
  int startsUnique;
  int subscribedUnique;
}

class MagnetStat {
  int id;
  String title;
  int downloads;
}
