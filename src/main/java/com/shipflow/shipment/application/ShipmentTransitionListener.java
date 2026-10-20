package com.shipflow.shipment.application;

/**
 * Extension point (observer) for side effects of status changes: scorecards, lead-time histograms,
 * delivery-risk stats, merchant webhooks... Runs inside the transition's transaction, so effects are atomic
 * with the status change (and roll back with it on optimistic-lock retries).
 */
public interface ShipmentTransitionListener {
    void onTransition(TransitionEvent event);
}
