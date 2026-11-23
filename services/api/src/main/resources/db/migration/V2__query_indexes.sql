CREATE INDEX venues_owner ON venues(organizer_id,name);
CREATE INDEX events_owner ON events(organizer_id,starts_at);
CREATE INDEX event_seats_tier ON event_seats(event_id,pricing_tier_id);
CREATE INDEX bookings_event_confirmed ON bookings(event_id) WHERE status='CONFIRMED';
CREATE INDEX provider_events_payment ON provider_events(payment_id);
CREATE INDEX audit_target ON audit_logs(target,id DESC);
