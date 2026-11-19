# Thin wrappers around the documented commands. On Windows without make, run the commands
# after each target directly.
.PHONY: setup hooks image up-local api worker web dev-db seed test test-java test-web lint fmt typecheck benchmark

setup:            ## install the web app's dependencies, and the commit-message hook (JDK 22 or later)
	cd apps/web && npm ci
	git config core.hooksPath .githooks

hooks:            ## check commit messages against Conventional Commits on every commit
	git config core.hooksPath .githooks

image:            ## build the Java API's image, archsim-app:local, for docker-compose.local.yml (Jib, into the local Docker)
	./gradlew :archsim-app:jibDockerBuild

up-local: image   ## run the whole stack on images built from this checkout, not the release's
	docker compose -f docker-compose.yml -f docker-compose.local.yml up --build

api:              ## build and run the API on http://127.0.0.1:8000 (needs PostgreSQL: make dev-db)
	./gradlew :archsim-app:bootJar
	java -jar archsim-app/build/libs/archsim-app.jar api

worker:           ## run a simulation worker on the API's database (three runs at once)
	./gradlew :archsim-app:bootJar
	ARCHSIM_MAX_CONCURRENT_RUNS=3 java -Xmx2g -XX:+ExitOnOutOfMemoryError -jar archsim-app/build/libs/archsim-app.jar worker

web:              ## run the web app on http://localhost:3000
	cd apps/web && npm run dev

dev-db:           ## start PostgreSQL only, where the API and the workers look by default
	docker compose up -d postgres

seed:             ## create one project per example in the default workspace (start the API first)
	./gradlew :archsim-app:bootJar
	java -jar archsim-app/build/libs/archsim-app.jar seed

test: test-java test-web

test-java:        ## the Java build: formatting, Checkstyle, Error Prone and NullAway, tests
	./gradlew build

test-web:
	cd apps/web && npm test

lint:
	./gradlew checkFormat checkstyleMain checkstyleTest
	cd apps/web && npm run lint

fmt:
	./gradlew format

typecheck:
	cd apps/web && npm run typecheck

benchmark:        ## accuracy benchmarks (Docker; about 1.5 hours, machine otherwise idle), then rewrite docs/validation.md and accuracy.json
	./gradlew :archsim-benchmarks:run --args="all"
	./gradlew :archsim-benchmarks:run --args="baselines"
	./gradlew :archsim-benchmarks:run --args="report --json"
