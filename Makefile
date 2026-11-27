FAULTS ?= sd_card_degrading:2,flaky_connectivity:4,firmware_clock_skew:8,reboot_loop:1,goes_dark:1,bad_gps:1

VUS ?= 50
DURATION ?= 60s

.PHONY: help infra down build test run-ingestion run-health run-safety simulate up-all smoke demo replay-dlt investigate load-test

help: ## Lista os comandos
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

infra: ## Sobe Kafka, Postgres, Prometheus e Grafana
	docker compose up -d

down: ## Derruba tudo (e apaga volumes)
	docker compose --profile apps down -v

build: ## Compila, testa e gera os jars
	./gradlew build bootJar :device-simulator:installDist

test: ## Roda os testes (unitários + Testcontainers)
	./gradlew test

run-ingestion: ## Gateway gRPC (porta 9090) + actuator (8081)
	./gradlew :services:ingestion-service:bootRun

run-health: ## Saúde de dispositivos + GraphQL (8082/graphiql)
	./gradlew :services:device-health-service:bootRun

run-safety: ## Processador de eventos de segurança (8083)
	./gradlew :services:safety-event-processor:bootRun

simulate: ## Simulador de frota. Ex.: make simulate FAULTS=firmware_clock_skew:10
	FAULTS=$(FAULTS) ./gradlew :device-simulator:run

up-all: build ## Sobe tudo em containers, inclusive o simulador
	docker compose --profile apps up -d --build

smoke: ## Verifica o fluxo de ponta a ponta (serviços e simulador já rodando)
	./scripts/smoke.sh

demo: up-all ## Sobe tudo em containers e roda o smoke test
	TIMEOUT=240 ./scripts/smoke.sh

replay-dlt: ## Mostra a DLT (dry run). Para republicar: ./scripts/replay-dlt.sh --apply
	./scripts/replay-dlt.sh

investigate: ## Gera o dataset sintético e roda as queries do postmortem 001
	pip install -q -r analytics/requirements.txt && python analytics/investigate.py

load-test: ## Teste de carga no gateway com k6. Ex.: make load-test VUS=200 DURATION=2m
	@command -v k6 >/dev/null || { echo "k6 não encontrado: https://grafana.com/docs/k6/latest/set-up/install-k6/"; exit 1; }
	mkdir -p build/load
	VUS=$(VUS) DURATION=$(DURATION) k6 run --summary-export build/load/summary.json scripts/load/gateway.js
