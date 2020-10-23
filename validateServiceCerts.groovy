//Use Jenkins to run this job
//This util sends mail to team with owner tag on project when one of service certs is going to expire within X days
import java.util.Date
import groovy.time.TimeCategory 
import groovy.time.TimeDuration
import java.text.SimpleDateFormat
def days = 60
def verdadArray = [:]
def clusters = [
    cluster_1: 'cluster_1 nice name',
    cluster_2: 'cluster_2 nice name'
]
def format = "yyyy-MM-dd'T'HH:mm:ss"
def now = new Date()
def dateNow =  java.time.LocalDateTime.now()
dateNow = new SimpleDateFormat(format).parse("${dateNow}")

pipeline {
    agent {
        label 'your slave'
    }
    stages {
        stage('Run check') {
            steps {
                script {
                    println "Check interval: ${days} days"
                    clusters.each { clusterCode, clusterName ->
                        openshift.withCluster(clusterCode) {
                            projects = openshift.selector('projects')
                            projects.withEach { project ->
                                projectSelector = project.object()
                                //if any group to exclude place it down
                                if (projectSelector.metadata.labels?.owner && projectSelector.metadata?.labels.owner == 'exclude-group') {
                                    println 'Skipping project ' + projectSelector.metadata.name + " on " + clusterName + " cluster"
                                } else {
                                    openshift.withProject(projectSelector.metadata.name) {
                                        println "Checking ${projectSelector.metadata.name} on ${clusterName} cluster"
                                        if (projectSelector.metadata.labels?.owner && !verdadArray."${projectSelector.metadata.labels.owner}") {
                                            verdadArray."${projectSelector.metadata.labels.owner}" = []
                                        }
                                        def secretList = openshift.selector('secret')
                                        secretList.withEach {
                                            thisSecret = openshift.selector("${it.name()}").object()
                                            if (thisSecret.type == 'kubernetes.io/tls' && thisSecret.metadata?.annotations?.'service.alpha.openshift.io/expiry') {
                                                expiryDate =  thisSecret.metadata.annotations['service.alpha.openshift.io/expiry']
                                                
                                                expiryDate = new SimpleDateFormat(format).parse(expiryDate)
                                                TimeDuration td = TimeCategory.minus( expiryDate, dateNow )
                                                
                                                println "Expiry date for secret ${thisSecret.metadata.name} is ${expiryDate}"
                                                if (td.days < days && projectSelector.metadata.labels?.owner) {
                                                    verdadArray."${projectSelector.metadata.labels.owner}".add("Cert ${thisSecret.metadata.name} from <b>${openshift.project()}</b> on ${clusterName} cluster is going to expire in ${td.days} days") 
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('Send reports') {
            steps { 
                script {
                    verdadArray.each {
                        if (it.value && it.key) {
                            echo "Will send it to: ${it.key}@santander.pl"
                            alerts = it.value.join('<br>')
                            emailext body: """You have received this message because certificates with expiration date with less than ${days} days have been found. Please check list below and link to instruction provided at the bottom of this mail.

                            <p>Alerts:
                            <br>${alerts}</p>

                            """,
                            mimeType: 'text/html',
                            subject: 'OpenShift service cert alert',
                            to: "${it.key}@somemail.com"
                        }
                    }
                }
            }
        }
    }
    post {
        unsuccessful {
            emailext body: '''Certs checker failed. Check <a href='${ENV, var="RUN_DISPLAY_URL"}'>${ENV, var="JOB_NAME"} [${ENV, var="BUILD_NUMBER"}]</a>''',
            mimeType : 'text/html',
            to: 'somebody@somemail.com',
            subject: 'OpenShift service cert alert pipeline FAILED'
        }
       cleanup {
            cleanWs()
        }
    }
}
