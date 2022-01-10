#!/usr/local/bin/fish

for c in (find /Users/tim/workspace/account-modelling/ing-codegen-akka/results/2017-07-24T10:01:03.526+02:00-batch/*/ -type d -maxdepth 0)
  set -l REPORTNAME (basename $c)
  echo $REPORTNAME
  R -e "rmarkdown::render('/Users/tim/workspace/account-modelling/ing-codegen-akka/results/reports/TryOut.Rmd', output_file=paste('$c/../report', '$REPORTNAME', '.html', sep=''))" --args -f $c
end