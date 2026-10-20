package com.shipflow.network;

import java.time.ZoneId;

public record CountryInfo(String code, String currency, ZoneId zone) {
}
