package io.konzert.examples.incident.sensor;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/** What the sensor currently believes is wrong — for the operator, not for the agents. */
@Path("/api/sensor")
@Produces(MediaType.APPLICATION_JSON)
public class SensorResource {

    @Inject
    Sensor sensor;

    @GET
    public Map<String, Object> state() {
        return Map.of("open", sensor.openIncidents());
    }
}
