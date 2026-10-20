package com.shipflow.network;

public record PincodeInfo(String code, String zoneCode, long hubId, String city, double lat, double lng,
                          String countryCode) {
}
