package com.tripplanner.agentic.flow;

import com.tripplanner.model.TripPlan;
import com.tripplanner.model.TripRequest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;

@Alternative
@ApplicationScoped
@Priority(1)
public class PilotPlanAdapter extends TripPlannerFlowAdapter {
    @Override
    public TripPlan planFromRequest(TripRequest request) {
        return new TripPlan(
                new TripPlan.VehicleRecommendation("SUV", "Generic SUV", "Fixture recommendation", null),
                "Local fixture route",
                List.of(new TripPlan.DayItinerary(1, "Arrival", "Explore the coast", "Synthetic destination")),
                new TripPlan.CostEstimate("$120", "$80", "$10", "$200", "$100", "$50", "$560"));
    }
}
