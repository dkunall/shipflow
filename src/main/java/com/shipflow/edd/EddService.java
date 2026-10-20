package com.shipflow.edd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.network.HubInfo;
import com.shipflow.network.NetworkDirectory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Application wrapper: loads calendars + lane history, delegates to the pure {@link PromiseDateCalculator}. */
@Service
public class EddService {
    private final NetworkDirectory network;
    private final LaneLeadTimeRepository lanes;
    private final ShipFlowProperties.Edd props;
    private final ObjectMapper mapper;

    public EddService(NetworkDirectory network, LaneLeadTimeRepository lanes, ShipFlowProperties props,
                      ObjectMapper mapper) {
        this.network = network;
        this.lanes = lanes;
        this.props = props.edd();
        this.mapper = mapper;
    }

    public LocalDate dispatchDate(HubInfo origin, Instant bookedAt) {
        return PromiseDateCalculator.dispatchDate(bookedAt, origin.zone(), origin.cutoffLocalTime(),
                network.calendar(origin.countryCode()));
    }

    public PromiseDateCalculator.Result promise(HubInfo origin, String destinationCountry, long carrierId, String zone,
                                                int slaDays, Instant bookedAt) {
        return PromiseDateCalculator.calculate(new PromiseDateCalculator.Input(bookedAt, origin.zone(),
                origin.cutoffLocalTime(), network.calendar(origin.countryCode()), network.calendar(destinationCountry),
                slaDays, lanes.histogram(carrierId, origin.id(), zone), props.leadTimePercentile(),
                props.minLaneSamples()));
    }

    public String explain(PromiseDateCalculator.Result r, int slaDays) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dispatchDate", r.dispatchDate().toString());
        m.put("bookedAfterCutoff", r.bookedAfterCutoff());
        m.put("transitBusinessDays", r.transitDays());
        m.put("transitSource", r.transitSource());
        m.put("carrierSlaDays", slaDays);
        m.put("laneSamples", r.laneSamples());
        m.put("promisedDate", r.promisedDate().toString());
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
