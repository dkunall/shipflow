package com.shipflow.booking;

import com.shipflow.booking.application.CsvShipmentParser;
import com.shipflow.shipment.domain.PaymentMode;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvShipmentParserTest {
    private static final String HEADER = "merchant_order_ref,origin_hub,name,phone,address_line1,city,pincode,weight_g,payment_mode,cod_amount,declared_value,currency\n";

    @Test
    void parsesQuotedFieldsCommasAndEscapedQuotes() throws Exception {
        var rows = CsvShipmentParser.parse(new StringReader(HEADER
                + "A1,HUB,\"Doe, \"\"JD\"\" John\",+971500000000,\"Flat 1, Tower \"\"B\"\"\",Dubai,P1,700,COD,50.25,50.25,AED\r\n"
                + "\n"), 10);
        assertThat(rows).hasSize(1);
        var r = rows.get(0).request();
        assertThat(rows.get(0).error()).isNull();
        assertThat(r.consignee().name()).isEqualTo("Doe, \"JD\" John");
        assertThat(r.consignee().addressLine1()).isEqualTo("Flat 1, Tower \"B\"");
        assertThat(r.paymentMode()).isEqualTo(PaymentMode.COD);
        assertThat(r.codAmount().amount()).isEqualByComparingTo("50.25");
        assertThat(r.weightGrams()).isEqualTo(700);
    }

    @Test
    void badRowsCarryLineNumberedErrorsWithoutFailingTheFile() throws Exception {
        var rows = CsvShipmentParser.parse(new StringReader(HEADER
                + "A1,HUB,N,+971500000000,Addr 1,Dubai,P1,abc,PREPAID,,10,AED\n"
                + "A2,HUB,N,+971500000000,Addr 1,Dubai,P1,100,BARTER,,10,AED\n"), 10);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).error()).contains("weight_g");
        assertThat(rows.get(0).line()).isEqualTo(2);
        assertThat(rows.get(1).error()).containsIgnoringCase("payment_mode");
    }

    @Test
    void structuralProblemsFailFast() {
        assertThatThrownBy(() -> CsvShipmentParser.parse(new StringReader("a,b\n1,2\n"), 10))
                .hasMessageContaining("missing CSV column");
        assertThatThrownBy(() -> CsvShipmentParser.parse(new StringReader(HEADER + "x\ny\nz\n"), 2))
                .hasMessageContaining("too many rows");
        assertThatThrownBy(() -> CsvShipmentParser.parse(new StringReader(""), 2)).hasMessageContaining("empty");
    }
}
