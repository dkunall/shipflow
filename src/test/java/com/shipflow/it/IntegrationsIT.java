package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.support.AbstractPostgresIT;
import com.shipflow.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bulk CSV upload and pluggable e-store order adapters. */
class IntegrationsIT extends AbstractPostgresIT {
    private static final String CSV = """
            merchant_order_ref,origin_hub,name,phone,address_line1,address_line2,landmark,city,pincode,weight_g,payment_mode,cod_amount,declared_value,currency
            B-1,DXB-HUB,Omar Ali,+971501111111,"Flat 301, Marina Heights",,near metro,Dubai,DXB-001,900,PREPAID,,120.00,AED
            B-2,DXB-HUB,"Sara ""S"" Noor",971502222222,Villa 7 Jumeirah 1,,,Dubai,DXB-002,2500,COD,75.50,75.50,AED
            B-3,DXB-HUB,Bad Weight,+971503333333,Somewhere 1,,,Dubai,DXB-001,-5,PREPAID,,10.00,AED
            B-4,DXB-HUB,Nowhere,+971504444444,Unknown street,,,Dubai,ZZZ-999,500,PREPAID,,10.00,AED
            B-1,DXB-HUB,Omar Ali,+971501111111,"Flat 301, Marina Heights",,near metro,Dubai,DXB-001,900,PREPAID,,120.00,AED
            """;

    @Test
    void bulkCsvCreatesValidRowsAndReportsEachFailure() throws Exception {
        thirdParty("FAST", "10.00", 2, 100);
        JsonNode report = body(mvc.perform(post("/api/v1/shipments/bulk").header("Authorization", TestJwt.merchant(m1))
                .contentType("text/csv").content(CSV)).andExpect(status().isOk()));
        assertThat(report.get("total").asInt()).isEqualTo(5);
        assertThat(report.get("created").asInt()).isEqualTo(2);
        assertThat(report.get("duplicates").asInt()).isEqualTo(1);
        assertThat(report.get("rows").get(2).get("status").asText()).isEqualTo("INVALID");
        assertThat(report.get("rows").get(3).get("error").asText()).containsIgnoringCase("pincode");
        assertThat(count("SELECT count(*) FROM shipment")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT consignee_name FROM shipment WHERE merchant_order_ref='B-2'", String.class))
                .isEqualTo("Sara \"S\" Noor");
        assertThat(count("SELECT count(*) FROM shipment WHERE status = 'ALLOCATED' AND carrier_awb IS NOT NULL")).isEqualTo(2);

        // re-upload is safe: everything is a duplicate now
        JsonNode again = body(mvc.perform(multipart("/api/v1/shipments/bulk")
                .file(new MockMultipartFile("file", "orders.csv", "text/csv", CSV.getBytes()))
                .header("Authorization", TestJwt.merchant(m1))).andExpect(status().isOk()));
        assertThat(again.get("created").asInt()).isZero();
        assertThat(count("SELECT count(*) FROM shipment")).isEqualTo(2);
    }

    @Test
    void bulkCsvMissingColumnIs400() throws Exception {
        mvc.perform(post("/api/v1/shipments/bulk").header("Authorization", TestJwt.merchant(m1))
                .contentType("text/csv").content("merchant_order_ref,name\nX,Y\n")).andExpect(status().isBadRequest());
    }

    @Test
    void shopifyOrderWebhookIsMappedAndIdempotentByOrderId() throws Exception {
        thirdParty("FAST", "10.00", 2, 100);
        String order = """
                {"id": 820982911946154508, "name": "#1001", "currency": "AED", "total_price": "199.00",
                 "financial_status": "pending", "payment_gateway_names": ["Cash on Delivery (COD)"],
                 "total_weight": 1500,
                 "shipping_address": {"first_name": "Layla", "last_name": "Hassan", "phone": "+971 50 555 0101",
                   "address1": "Apartment 1204, Burj Views Tower B", "address2": null, "company": null,
                   "city": "Dubai", "zip": "DXB-001", "latitude": 25.19, "longitude": 55.27}}
                """;
        JsonNode s = body(mvc.perform(post("/api/v1/integrations/shopify/orders").param("originHub", HUB)
                        .header("Authorization", TestJwt.merchant(m1)).contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isCreated()));
        assertThat(s.get("merchantOrderRef").asText()).isEqualTo("shopify-820982911946154508");
        assertThat(s.get("paymentMode").asText()).isEqualTo("COD");
        assertThat(s.get("source").asText()).isEqualTo("SHOPIFY");
        assertThat(s.get("weightGrams").asInt()).isEqualTo(1500);
        // Shopify retries the same webhook: the order id is the idempotency key
        mvc.perform(post("/api/v1/integrations/shopify/orders").param("originHub", HUB)
                        .header("Authorization", TestJwt.merchant(m1)).contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(count("SELECT count(*) FROM shipment")).isEqualTo(1);
    }

    @Test
    void genericAdapterAndUnknownSource() throws Exception {
        thirdParty("FAST", "10.00", 2, 100);
        String generic = shipmentJson("G-1", "PREPAID", null).replace("\"originHubCode\":\"DXB-HUB\",", "");
        mvc.perform(post("/api/v1/integrations/generic/orders").param("originHub", HUB)
                        .header("Authorization", TestJwt.merchant(m1)).contentType(MediaType.APPLICATION_JSON).content(generic))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("GENERIC"));
        mvc.perform(post("/api/v1/integrations/magento/orders").param("originHub", HUB)
                        .header("Authorization", TestJwt.merchant(m1)).contentType(MediaType.APPLICATION_JSON).content(generic))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNSUPPORTED_SOURCE"));
    }
}
