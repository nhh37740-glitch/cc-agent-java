pipeline {
    agent { label 'media-workspace-agent' }
    options { timestamps(); disableConcurrentBuilds() }
    parameters {
        booleanParam(
            name: 'DeployDemo',
            defaultValue: false,
            description: 'After verification and image build, replace the demo container on 127.0.0.1:18102 with health checks and rollback.'
        )
    }
    stages {
        stage('Verify and package') {
            steps {
                sh '''
                    set -eu
                    sudo docker build --target build \
                      --build-arg SOURCE_COMMIT="$(git rev-parse HEAD)" \
                      --build-arg SOURCE_TREE="$(git rev-parse HEAD^{tree})" \
                      --build-arg SOURCE_DIRTY="$(test -z "$(git status --porcelain)" && echo false || echo true)" \
                      -t "cc-agent-java-build:${BUILD_NUMBER}" .
                    container_id="$(sudo docker create "cc-agent-java-build:${BUILD_NUMBER}")"
                    trap 'sudo docker rm -f "$container_id" >/dev/null' EXIT
                    mkdir -p dist
                    sudo docker cp "$container_id:/src/dist/." dist/
                '''
                archiveArtifacts artifacts: 'dist/**', fingerprint: true
            }
        }
        stage('Runtime image') {
            steps {
                sh 'sudo docker build -t "cc-agent-java:${BUILD_NUMBER}" .'
            }
        }
        stage('Deploy demo') {
            when { expression { params.DeployDemo == true } }
            steps {
                sh 'bash scripts/deploy-demo.sh "$BUILD_NUMBER"'
            }
        }
    }
}
