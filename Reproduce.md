# Reproduce experiments results for Static Local Coordination Avoidance for Distributed Objects Artifacts

## Process

### Run in Docker

+ Make sure that you have docker and docker-compose installed and configured. Used version Docker version 19.03.2.
+ Import the stored docker image to your local docker setup: `docker load < rebel-runtime-lib_bench_latest.tar.gz`
+ Run the benchmarks using docker compose: `docker-compose -f docker-compose-bench.yml run $$`, replace $$ with one of the benchmarks:
    + `dep-indep`
    + `dep-indep-latency`
    + `transaction`
    + `transaction-latency`    
    + `tax`
    + `tax-latency`
+ All output data can be found in the `bench-output` directory. A csv for the data and jfrs for profiling information.


+ Note that running benchmarks takes quite a while and can best be done overnight.
+ Since the experiments are run on different hardware, there can be differences between your results and the results presented in the paper. 
+ To get a `jfr` file for each experiment, you need a patched `sbt-jmh` (https://github.com/ktoso/sbt-jmh/pull/172). 

### Run locally

+ Make sure you have sbt installed and active internet connection

Run in the `rebel-runtime-lib` directory one of the `command`s found in file `docker-compose-bench.yml`.

Alternatively run using docker, with volume mounted:
 
```
 docker run -it -u sbtuser \
   -w /home/sbtuser/rebel-lib \
   -v $(pwd):/home/sbtuser/rebel-lib \
   --rm hseeberger/scala-sbt:11.0.3_1.2.8_2.12.9 \
   sbt 'bench/jmh:run -prof jmh.extras.JFR:dir=/tmp/bench-output/ -rf csv -rff /tmp/bench-output/benchTP.csv -i 20 -wi 5 -f 1 com.ing.rebel.benchmark.vs.loca.depositwithdraw.*BenchmarkNoOp..*TP.*'
```