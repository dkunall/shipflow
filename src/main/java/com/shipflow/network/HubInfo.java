package com.shipflow.network;

import java.time.LocalTime;
import java.time.ZoneId;

public record HubInfo(long id, String code, String name, String countryCode, double lat, double lng,
                      LocalTime cutoffLocalTime, ZoneId zone) {
}
