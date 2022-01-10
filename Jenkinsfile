node {
  try {
    def sbtHome = tool 'sbt-0-13'
    env.JAVA_HOME="${tool 'jdk-oracle-8'}"
    env.PATH="${env.JAVA_HOME}/bin:${sbtHome}/bin:${env.PATH}"

    def sbt = "java -Dsbt.log.noformat=true -jar ${sbtHome}/bin/sbt-launch.jar"

    env.TIMEFACTOR = 4

    stage('Clone'){
      checkout scm
    }

    stage('Build') {
      sh "${sbt} clean compile"
    }

    stage('Test') {
      sh "${sbt} '; set parallelExecution in Test := false; test-quick -- -l com.ing.util.CassandraTag'"
    }

    stage('Integration Test') {
      sh "${sbt} '; set parallelExecution in Test := false; generated/it:test-quick -- -l com.ing.util.CassandraTag'"
    }

    stage('MultiJVM') {
      sh "${sbt} '; set parallelExecution in Test := false; generated/multi-jvm:test-quick -- -l com.ing.util.CassandraTag'"
    }

    stage('Deploy') {
      sh "${sbt} publishLocal"
    }

    stage('post-build') {
      slackSend channel: '#ing', color: 'good', message: "SUCCESSFUL: Job '<${env.BUILD_URL}|${env.JOB_NAME} [${env.BUILD_NUMBER}]>'"
    }
  } catch (e) {
      slackSend channel: '#ing', color: 'bad', message: "FAILED: Job '<${env.BUILD_URL}|${env.JOB_NAME} [${env.BUILD_NUMBER}]>'"
      throw e
    }
}
