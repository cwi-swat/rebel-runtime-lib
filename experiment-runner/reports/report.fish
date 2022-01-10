#!/usr/local/bin/fish

for c in (find /Users/tim/workspace/account-modelling/ing-codegen-akka/results/2017-09-11T16:15:14.504+02:00-PerformanceDebugOpenSystem/Inmem/*/ -type d -maxdepth 0)
  set -l REPORTNAME (basename $c)
  echo $REPORTNAME
  R -e "rmarkdown::render('/Users/tim/workspace/account-modelling/ing-codegen-akka/aws/reports/ExperimentReport.Rmd', output_file=paste('$c/../report', '$REPORTNAME', '.html', sep=''))" --args -f $c
end