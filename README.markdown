## Rebel Runtime Library

After cloning this project:

1. Go to the `rebel-runtime-lib` folder and launch SBT:

        $ sbt

### Compile everything and run all tests:

        > test

### Start the application:

        > run

+ Browse to http://localhost:8001/tree or http://localhost:8001/graph for visualisation
+ Browse to http://localhost:8080/ for http://localhost:8080/Account for REST endpoints

### Useful `sbt` commands

- `run1` - `run4`  (run local with shared level DB)
- `cassandra` (run a local C* instance on port 9042)
- `crun1` - `crun4` (run local with C*)
- `grun1` - `grun4` (run generated version from `generated` sources root with C*)
- `perf/gatling:test` - a performance test (on already running generated version)
- `perf/gatling:testOnly com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation`
- `AccountConsistencyChecker` - consistency checker for accounts (based on generated version, requires C*)
- `bench/jmh:run -i 10 -wi 10 -f 2 -t 1 com.ing.rebel.benchmark.JmhBenchmarks` - run micro benchmarks

### Default workflow

- `sbt cassandra` - start local Cassandra
- `sbt grun1` - start single node generated implementation
- `sbt perf/gatling:testOnly com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation` -  run a simple performance test

### Benchmarks + results
- `export DATE=(date +"%Y_%m_%d_%H_%M_%S"); sbt "bench/jmh:run -rf csv -rff jmh_$DATE.csv -i 5 -wi 5 -f 1 -t 1 com.ing.rebel.benchmark.*PersistenceBenchmark"`

### Docker

- `sbt generated/docker:publishLocal` - currently only works when rebuild all in IntelliJ is done, since it dumps all the files in the target/classes

- `docker-compose up` - start in local docker: c* + seed + node

- `sbt perf/docker:publishLocal` - gatling + scenario's in docker
run with: docker-compose run performance

#### AWS

- `sbt generated/ecr:createRepository`
- `sbt generated/ecr:login`
- `sbt generated/ecr:push` - implies previous steps
- `ecs-cli compose -f docker-compose-aws.yml up` - starts c* + seed + kamon metrics board

##### Quick

In sbt:

- `generated/ecr:push`
- `perf/ecr:push`
- `grafana_influxdb/ecr:push`

## Publish this locally in your .ivy folder to be able to build the generated code in another project.

This is needed because the generated code depends on this artifact as a library.

        $ sbt publishLocal

## Disclaimer

This code is used for research and proof of concept. It is currently in no state to be used for production.

## WIP

### Scala 2.13

Held back by dependencies:

- perf: Gatling, stuck on 2.12
- aws: monix-monadless, stuck on 2.12