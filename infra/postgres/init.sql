-- One database per service: services never read each other's tables (they integrate via Kafka/APIs).
create database fleet_health;
create database fleet_safety;
