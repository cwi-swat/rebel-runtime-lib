if (!require("pacman")) install.packages("pacman")
devtools::install_github("mikabr/ggpirate") # comment to not do this every time
pacman::p_load(ggplot2, 
               # lattice, zoo,
               data.table, anytime, usl, jsonlite, plotly, 
               # lazyeval, 
               yarrr, lubridate, optparse, RColorBrewer, bit64, knitr, memoise, rmarkdown, ggpirate, parallel)

# library(ggplot2)
# library(lattice)
# library(zoo)
# library(data.table)
# library(anytime)
# library(usl)
# library(jsonlite)
# library(plotly)
# library(lazyeval)
# library(yarrr)
# library(lubridate)

options(echo=TRUE)
# library(optparse)

# if (!require("RColorBrewer")) {
#   install.packages("RColorBrewer")
#   library(RColorBrewer)
# }
# palette(brewer.pal(n = 12, name = "Set3"))

# don't use grey theme for ggplot
theme_set(theme_bw(base_size=12))
theme_update(
  panel.background = element_rect(fill = "transparent", colour = NA),
  panel.border = element_rect(fill = "transparent", colour = NA),
  plot.background = element_rect(fill = "transparent", colour = NA))
opts_chunk$set(dev.args=list(bg="transparent"))

config(plot_ly(), colors="Set3")

# Calculate the number of cores
no_cores <- detectCores() - 1

loadCsv <- function(csvFilePath) {
  csv <- fread(csvFilePath, sep = ",", header= TRUE, colClasses = c("time" = "integer64"), stringsAsFactors = T)
  # print(csv)
  # csv <- read.csv(file=csvFilePath,head=TRUE,sep=",", colClasses = c("time" = "character"))
  # print(csv)
  csv$time <-  tryCatch(anytime(csv$time / 1000000000), error = function(e) csv$time) 
  # as.POSIXct(gatling$time / 1000000000, origin="1970-01-01")
  # print(csv)
  # data.table(csv)
  csv
}

getMetricsData <- memoise(function(experiment, dataName, trimRun = TRUE) { 
  # print(sprintf("unmemoised call to %s %s", experiment$metricsDir, dataName))
  csv <- loadCsv(sprintf("%s/%s.csv", experiment$metricsDir, dataName)) 
  if(trimRun) {
    csv <- csv[time >= experiment$performanceStartTime & time <= experiment$performanceEndTime,]
  }
  csv
  })

loadExperiment <- function(experimentDir) { 
  testDescriptionFile <- sprintf("%s/test.json", experimentDir)
  metricsDir <- sprintf("%s/metrics/", experimentDir)
  # print(exp)
  
  print(testDescriptionFile)
  json <- fromJSON(readLines(testDescriptionFile, warn=FALSE))
  
  # Find start of real performance test
  parsedDuration <- unlist(strsplit(json$duration, " "))
  maxDuration <- eval(parse(text=paste0("d", parsedDuration[2], "(", parsedDuration[1], ")")))
  # print(paste0("maxDuration ", maxDuration))
  # warmup + wait + rampuptime
  
  warmupTime <- if(is.null(json$performanceConfig$`rebel.scenario.warmup-duration`)) {
    (30 * 2 + (maxDuration) / 12) 
  } else {
    # parsedWarmupDuration <- unlist(strsplit(json$performanceConfig$`rebel.scenario.warmup-duration`, " "))
    # warmupDuration <- eval(parse(text=paste0("d", parsedWarmupDuration[2], "(", parsedWarmupDuration[1], ")")))
    # warmupDuration
    duration(json$performanceConfig$`rebel.scenario.warmup-duration`)
  }
  # print(paste0("warmupTime ", warmupTime))
  
  gatlingFile <- sprintf("%s/gatling.csv", metricsDir)
  gatlingFull <- loadCsv(gatlingFile)
  
  smallestTime <- min(gatlingFull$time)
  performanceStartTime <- smallestTime + (warmupTime*1.5) # times two to be certain
  
  # print(performanceStartTime)
  
  endTime <- max(gatlingFull$time)
  endCutOff <- endTime - 15
  # print(endCutOff)
  
  
  gatlingUsersFile <- sprintf("%s/gatling.users.csv", metricsDir)
  gatlingUsersFull <- loadCsv(gatlingUsersFile)

  # filter on start time
  gatling <- gatlingFull[time >= performanceStartTime & time <= endCutOff,]
  # print(gatling)
  gatlingUsers <- gatlingUsersFull[time >= performanceStartTime & time <= endCutOff,]
  
  # print(paste0("first metric ", smallestTime))
  # print(paste0("performanceStartTime ", performanceStartTime))
  # print(paste0("endCutOff ", endCutOff))
  # print(paste0("last metric ", endTime))
  
  
  db <- tryCatch( {
    loadCsv(sprintf("%s/db.jvm.csv", metricsDir))
    }, error=function(e) {
      tryCatch( {
        loadCsv(sprintf("%s/servers.jvm.csv", metricsDir))
      }, error=function(e) { 
        tryCatch( {
          loadCsv(sprintf("%s/db.eu-central-1.csv", metricsDir))
        }, error=function(e) {
          NULL
        }
   ) } ) } )
  dbDuringExp <- tryCatch( {
    db[time >= performanceStartTime & time <= endCutOff,]
  }, error=function(e) {NULL} )
  
  # print(json$clusterSize)
  
  list(
    experimentDir = experimentDir,
    metricsDir = metricsDir,
    testDescription = json,
    gatling = gatling,
    gatlingFull = gatlingFull,
    gatlingUsers = gatlingUsers,
    gatlingUsersFull = gatlingUsersFull,
    db = db,
    dbDuringExp = dbDuringExp,
    performanceStartTime = performanceStartTime,
    performanceEndTime = endCutOff,
    warmupTime = warmupTime
  )
}

safeLoadExperiment <- function(experimentDir) {
  tryCatch({
    loadExperiment(experimentDir)
  }, error=function(e){
     # print((sys.calls()))
    cat("ERROR :",conditionMessage(e), "\n");
    # traceback()
    NULL})
}


gatlingTotals <- memoise(function(experiment, rampExperiment = FALSE) {
  print(experiment$experimentDir)
  
  # print(rampExperiment)
  # print(getBestStep(experiment))
  # print(experiment$gatling)
  dataUsed <- if (rampExperiment) getBestStep(experiment)$data else experiment$gatling
  
  ok <- dataUsed[request!="allRequests" & status=="ok"]
  # okTotal <- ok[,list(count = sum(count)), by = time]
  ok <- ok[,list(count = sum(count)), by = time]
  
  all <- dataUsed[request=="allRequests" & status=="all"]
  all <- all[,list(count = sum(count), percentiles50 = as.numeric(median(percentiles50)),
                   percentiles95 = as.numeric(median(percentiles95)),
                   percentiles99 = as.numeric(median(percentiles99)), 
                   `percentiles99-9` = as.numeric(median(`percentiles99-9`))), by = time]

  ko <- dataUsed[request!="allRequests" & status=="ko"]
  # TODO make sure this works correctly for multiple hosts
  ko <- ko[,list(count = sum(count)), by = time]
  # koTotal <- ko[,list(count = sum(count)), by = time]
  
  # joining ok to all
  all[ok, ok:=i.count, on = c(time="time")]
  
  # join ko to all
  all[ko, ko:=i.count, on = c(time="time")]
  allTotal <- all[,list(count = sum(count), ok=sum(ok), ko=sum(ko)), by = time]
  
  # TODO maybe limit to best time window in case of ramp
  gatlingUsers <- experiment$gatlingUsers[request == "allUsers", list(active = sum(active)), by = time]
  
  # print(allTotal)
  
  list(
    # ok = ok,
    # okTotal = okTotal,
    all = all,
    allTotal = allTotal,
    # ko = ko,
    # koTotal = koTotal
    users = gatlingUsers
  )
})

getN <- function(nName, testDescription) {
  # print(paste0("n = ", nName))
  
  n <- ifelse(nName == "db-cluster-sizes",  testDescription$dbClusterSize,
              ifelse(nName == "cluster-sizes", testDescription$clusterSize,
                     ifelse(nName == "user-counts", testDescription$testUsers, 
                            ifelse(nName == "performance-node-sizes", testDescription$performanceNodeSize, 
                                   ifelse(nName == "performance-throttles", testDescription$performanceThrottle, 
                                          NA)))))
  n
}

extractNodeCountVsThroughput <- function(exp, nName, rampExperiment = FALSE) { 
  gt <- gatlingTotals(exp, rampExperiment)
  # print(gt$allTotal)
  # print(mean(gt$all$percentiles99, na.rm=TRUE))
  
  # print(gt$users)

  # print(gt);
    
  # exp$testDescription$
  
  n = getN(nName, exp$testDescription)
  
  med <- median(gt$allTotal$ok)
  failures <- mean(gt$allTotal$ko)
  
  # dbCpu <- exp$dbDuringExp
  
  # dbCpu <- exp$dbDuringExp$os.processCpuLoad + exp$dbDuringExp$os.systemCpuLoad
  
  # os.processCpuLoad since it seems to be higher than systemCpuLoad
  dbCpu <- exp$dbDuringExp$os.processCpuLoad
  
  dbCpu.med = median(dbCpu, na.rm=TRUE)
  # nodeCpuMedian = median(nodeCpu, na.rm=TRUE)
  
  nodeCpuData <- NULL
  nodeCpu = NULL
  nodeCpuMedian = NULL
  try({
    nodeCpuData <- getMetricsData(exp, "host.cpu") #loadCsv(sprintf("%s/host.cpu.csv", exp$metricsDir))
    
    nodeCpu = nodeCpuData[mode=='combined']$p99.9
    nodeCpuMedian = median(nodeCpu, na.rm=TRUE)
  })
  
  result <- list(
    # use n to signal relevant variation metric
    n = n,
    throughput = med,
    users = median(gt$users$active, na.rm=TRUE),
    # throughput.all = gt$allTotal$ok,
    throughput.max = max(gt$allTotal$ok, na.rm=TRUE) - med,
    throughput.min = med - min(gt$allTotal$ok, na.rm=TRUE),
    throughput.sd = sd(gt$allTotal$ok, na.rm=TRUE),
    failures = failures,
    failures.sd = sd(gt$allTotal$ko, na.rm=TRUE),
    failures.max = max(gt$allTotal$ko, na.rm=TRUE) - failures,
    failures.min = failures - min(gt$allTotal$ko, na.rm=TRUE),
    `percentiles99-9` = max(gt$all$`percentiles99-9`, na.rm=TRUE),
    percentiles99 = max(gt$all$percentiles99, na.rm=TRUE),
    percentiles95 = max(gt$all$percentiles95, na.rm=TRUE),
    percentiles50 = max(gt$all$percentiles50, na.rm=TRUE),
    # nodeCpu = nodeCpu,
    cpu = nodeCpuMedian,
    cpu.max = max(nodeCpu, na.rm=TRUE) - nodeCpuMedian,
    cpu.min = nodeCpuMedian - min(nodeCpu, na.rm=TRUE),
    dbCpu = dbCpu.med,
    dbCpu.max = max(dbCpu, na.rm=TRUE) - dbCpu.med,
    dbCpu.min = dbCpu.med - min(dbCpu, na.rm=TRUE)
  )
  unlist(result)
}

# extractNodeCountVsThroughputBestStep <- function(exp, nName) { 
#   gt <- gatlingTotals(exp)
#   # print(gt$allTotal)
#   # print(mean(gt$all$percentiles99, na.rm=TRUE))
#   
#   # print(gt$users)
#   
#   # print(gt);
#   
#   # exp$testDescription$
#   
#   n = getN(nName, exp$testDescription)
#   
#   bestStep = getBestStep(exp)
#   
#   ok <- bestStep[request!="allRequests" & status=="ok"][,list(count = sum(count)), by = time]
#   ko <- bestStep[request!="allRequests" & status=="ko"][,list(count = sum(count)), by = time]
#   
#   med <- median(ok$count)
#   failures <- mean(ko$count)
#   
#   # dbCpu <- exp$dbDuringExp
#   
#   # dbCpu <- exp$dbDuringExp$os.processCpuLoad + exp$dbDuringExp$os.systemCpuLoad
#   
#   # os.processCpuLoad since it seems to be higher than systemCpuLoad
#   dbCpu <- exp$dbDuringExp$os.processCpuLoad
#   
#   dbCpu.med = median(dbCpu, na.rm=TRUE)
#   # nodeCpuMedian = median(nodeCpu, na.rm=TRUE)
#   
#   nodeCpuData <- NULL
#   nodeCpu = NULL
#   nodeCpuMedian = NULL
#   try({
#     nodeCpuData <- getMetricsData(exp, "host.cpu") #loadCsv(sprintf("%s/host.cpu.csv", exp$metricsDir))
#     nodeCpu = nodeCpuData[mode=='combined']$p99.9
#     nodeCpuMedian = median(nodeCpu, na.rm=TRUE)
#   })
#   
#   result <- list(
#     # use n to signal relevant variation metric
#     n = n,
#     throughput = med,
#     users = median(gt$users$active, na.rm=TRUE),
#     # throughput.all = gt$allTotal$ok,
#     throughput.max = max(ok$count, na.rm=TRUE) - med,
#     throughput.min = med - min(ok$count, na.rm=TRUE),
#     throughput.sd = sd(ok$count, na.rm=TRUE),
#     failures = failures,
#     failures.sd = sd(ko$count, na.rm=TRUE),
#     failures.max = max(ko$count, na.rm=TRUE) - failures,
#     failures.min = failures - min(ko$count, na.rm=TRUE),
#     `percentiles99-9` = max(gt$all$`percentiles99-9`, na.rm=TRUE),
#     percentiles99 = max(gt$all$percentiles99, na.rm=TRUE),
#     percentiles95 = max(gt$all$percentiles95, na.rm=TRUE),
#     percentiles50 = max(gt$all$percentiles50, na.rm=TRUE),
#     # nodeCpu = nodeCpu,
#     cpu = nodeCpuMedian,
#     cpu.max = max(nodeCpu, na.rm=TRUE) - nodeCpuMedian,
#     cpu.min = nodeCpuMedian - min(nodeCpu, na.rm=TRUE),
#     dbCpu = dbCpu.med,
#     dbCpu.max = max(dbCpu, na.rm=TRUE) - dbCpu.med,
#     dbCpu.min = dbCpu.med - min(dbCpu, na.rm=TRUE)
#   )
#   unlist(result)
# }


loadSimulationLogs <- memoise(function(experimentPath) {
  logs <- list.files(experimentPath, pattern = "simulation.log$", recursive = TRUE)
  
  simulations <- lapply(logs, function(log) {
    logPath <- paste0(experimentPath, "/", log)
    # print(logPath)
    simulation <- fread(logPath,
                        sep="\t", fill=TRUE, 
                        skip = "REQUEST",
                        # USER    AllToAll        1       START   1543937949102   1543937949102
                        col.names=c("type", "scenario", "userId", "request", "start", "end", "status", "errorMessage"),
                        colClasses = c("V5" = "numeric", "V6" = "numeric"))
    simulation[,hostname:=log]
    # simulation$hostname <- rep(log,nrow(simulation)) 
    simulation
  })
  # print(simulations)
  
  simulation <- rbindlist(simulations)
  
  dim(simulation)
  

  requests <- simulation[type=="REQUEST" & scenario != "warmup",]
  # print(head(requests))
  # requests$start <- anytime(requests$start / 1000)
  # requests$end <- anytime(requests$end / 1000)
  requests$duration <- requests$end - requests$start
  # head(requests)
  # dim(requests)
  
  # could be that we loose resolution
  requests$start <- anytime(requests$start / 1000)
  requests$end <- anytime(requests$end / 1000)
  
  requests
})

getBestStep <- memoise(function(experiment) {
  # lazy val nrOfSteps: Int = 10
  # lazy val levelLasting: FiniteDuration = (maxDuration / nrOfSteps).toCoarsest
  # lazy val rampLasting: FiniteDuration = (warmupDuration / nrOfSteps).toCoarsest
  # lazy val incrementBy: Double = (users.toDouble - warmupUsers) / nrOfSteps
  nrOfSteps <- if(is.null(experiment$testDescription$performanceConfig$`rebel.scenario.number-of-steps`)) {
    10
  } else {
    as.integer(experiment$testDescription$performanceConfig$`rebel.scenario.number-of-steps`)
  }
  maxDuration <- as.duration(experiment$testDescription$duration)
  levelLasting <- maxDuration / nrOfSteps
  warmUpDuration <- experiment$warmupTime
  rampLasting <- warmUpDuration / nrOfSteps # default value, should be gotting out of config if available
  
  startTime <- min(experiment$gatlingFull$time)
  
  steps <- list()
  best <- 0
  # bestResult <- list(
  #   data = data.table(),
  #   beginTime = startTime,
  #   endTime = bestEndTime,
  #   allSteps = list()
  # )
  
  bestData <- data.table()
  bestStartTime <- startTime
  bestEndTime <- startTime + levelLasting + rampLasting
  
  lastBest <- list()
  while(startTime < experiment$performanceEndTime) {
    nextStep <- startTime + levelLasting + rampLasting
    step <- experiment$gatlingFull[time <= nextStep & time > startTime, ]
    steps[[ as.character(startTime) ]] = step
    ok <- step[status == 'ok',]
    # Take the step with the best median OK throughput
    medianOk <- median(ok$count)
    # print(medianOk)
    if(medianOk > best ) {
      # capture last best
      lastBest = list(
        data = bestData,
        medianTP = best,
        beginTime = bestStartTime,
        endTime = bestEndTime,
        lastBest = lastBest
      )
      # new best
      best <- medianOk
      bestData <- step
      bestStartTime <- startTime
      bestEndTime <- nextStep
    }
    startTime <- nextStep
    # print(plot_ly(step, split=~status, x=~time, y=~count, mode = 'markers', type='scatter'))
  }
  
  cat("best step: ", best)
  list(
    data = bestData,
    medianTP = best,
    beginTime = bestStartTime,
    endTime = bestEndTime,
    allSteps = steps,
    lastBest = lastBest
  )
})