// Copy this file into each standalone service repository as `Jenkinsfile`.
// Set the corresponding DOCKER_REPOSITORY value in that file's environment block.
pipeline {
    agent { label 'taskflow-docker' }

    // The taskflow-docker agent provides Java 17, Maven, Node.js, npm, and Docker CLI.

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        skipDefaultCheckout(true)
    }

    environment {
        DOCKERHUB_NAMESPACE = 'sasi33'
        DOCKER_REPOSITORY = 'api-gateway'
        DOCKER_CREDENTIALS_ID = 'dockerhub-credentials'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                    env.BRANCH_TO_PUBLISH = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').replaceFirst(/^origin\//, '')
                    if (!(env.DOCKERHUB_NAMESPACE ==~ /[a-z0-9]+(?:[._-][a-z0-9]+)*/)) {
                        error('Set DOCKERHUB_NAMESPACE to your Docker Hub user or organization namespace.')
                    }
                    if (!(env.DOCKER_REPOSITORY ==~ /[a-z0-9]+(?:[._-][a-z0-9]+)*/)) {
                        error('Set DOCKER_REPOSITORY to this service Docker Hub repository name.')
                    }
                    echo "Building ${env.DOCKERHUB_NAMESPACE}/${env.DOCKER_REPOSITORY}:${env.IMAGE_TAG} from ${env.BRANCH_TO_PUBLISH}."
                }
            }
        }

        stage('Build and test') {
            steps {
                script {
                    if (fileExists('pom.xml')) {
                        sh 'mvn -B -ntp clean verify'
                    } else if (fileExists('package-lock.json')) {
                        sh 'npm ci'
                        sh '''
                            if node -e "process.exit(require('./package.json').scripts?.test ? 0 : 1)"; then
                                npm test -- --run
                            else
                                echo 'No frontend test script is configured; skipping tests.'
                            fi
                        '''
                        sh 'npm run build'
                    } else {
                        error('Expected a Maven pom.xml or frontend package-lock.json at repository root.')
                    }
                }
            }
        }

        stage('Build Docker image') {
            steps {
                sh '''
                    set -eu
                    docker build --pull --tag "$DOCKERHUB_NAMESPACE/$DOCKER_REPOSITORY:$IMAGE_TAG" .
                '''
            }
        }

        stage('Push to Docker Hub') {
            when {
                anyOf {
                    branch 'main'
                    branch 'master'
                }
            }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: "${DOCKER_CREDENTIALS_ID}",
                    usernameVariable: 'DOCKERHUB_USER',
                    passwordVariable: 'DOCKERHUB_TOKEN'
                )]) {
                    sh '''
                        set +x
                        printf '%s' "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USER" --password-stdin
                        docker push "$DOCKERHUB_NAMESPACE/$DOCKER_REPOSITORY:$IMAGE_TAG"
                        docker tag "$DOCKERHUB_NAMESPACE/$DOCKER_REPOSITORY:$IMAGE_TAG" "$DOCKERHUB_NAMESPACE/$DOCKER_REPOSITORY:dev"
                        docker push "$DOCKERHUB_NAMESPACE/$DOCKER_REPOSITORY:dev"
                        docker logout
                    '''
                }
            }
        }
    }

    post {
        always {
            junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
            script {
                if (env.DOCKERHUB_NAMESPACE && env.DOCKER_REPOSITORY && env.IMAGE_TAG) {
                    sh "docker image rm '${env.DOCKERHUB_NAMESPACE}/${env.DOCKER_REPOSITORY}:${env.IMAGE_TAG}' >/dev/null 2>&1 || true"
                    sh "docker image rm '${env.DOCKERHUB_NAMESPACE}/${env.DOCKER_REPOSITORY}:dev' >/dev/null 2>&1 || true"
                }
            }
        }
        failure {
            echo 'Build failed. Review the stage logs and test results.'
        }
    }
}
