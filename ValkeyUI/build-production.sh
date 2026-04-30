#!/bin/bash

# ===================================================================
# Script di Build e Push per Ambiente di Produzione - Valkey UI
# ===================================================================
# Questo script builda l'immagine Docker del servizio Valkey UI
# con le configurazioni corrette per l'ambiente di produzione e la
# pusha su un registry.
#
# Uso: ./build-production.sh [OPZIONI]
#
# Esempio:
#   ./build-production.sh -r docker.io/mycompany -v v1.0.0
# ===================================================================

set -e  # Exit on error

# Colori per output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

show_help() {
    echo ""
    echo "Uso: $0 [OPZIONI]"
    echo ""
    echo "Opzioni:"
    echo "  -r, --registry REGISTRY       URL del Docker Registry (es: docker.io/mycompany)"
    echo "  -v, --version VERSION         Versione dell'immagine (es: v1.0.0, latest)"
    echo "  -n, --name IMAGE_NAME         Nome dell'immagine (default: valkey-ui)"
    echo "  --skip-push                   Non esegue il push dell'immagine"
    echo "  --skip-tests                  Salta i test durante il build"
    echo "  -h, --help                    Mostra questo help"
    echo ""
    echo "Esempio:"
    echo "  $0 -r docker.io/mycompany -v v1.0.0"
    echo "  $0 -r docker.io/mycompany -v latest --skip-tests"
    echo ""
}

# Default values
DOCKER_REGISTRY="docker.io/leonardoquirini"
VERSION="1.0.0"
IMAGE_NAME="valkey-ui"
SKIP_PUSH=false
SKIP_TESTS=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -r|--registry)
            DOCKER_REGISTRY="$2"
            shift 2
            ;;
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        -n|--name)
            IMAGE_NAME="$2"
            shift 2
            ;;
        --skip-push)
            SKIP_PUSH=true
            shift
            ;;
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            log_error "Opzione sconosciuta: $1"
            show_help
            exit 1
            ;;
    esac
done

if [ -z "$DOCKER_REGISTRY" ]; then
    log_error "Registry non specificato. Usa -r o --registry"
    show_help
    exit 1
fi

if [ -z "$VERSION" ]; then
    log_error "Versione non specificata. Usa -v o --version"
    show_help
    exit 1
fi

FULL_IMAGE_NAME="${DOCKER_REGISTRY}/${IMAGE_NAME}:${VERSION}"
LATEST_IMAGE_NAME="${DOCKER_REGISTRY}/${IMAGE_NAME}:latest"

echo ""
echo "╔════════════════════════════════════════════════════════╗"
echo "║      VALKEY UI - Build Production Image                ║"
echo "╚════════════════════════════════════════════════════════╝"
echo ""

log_info "Configurazione build:"
echo "  Registry:             ${DOCKER_REGISTRY}"
echo "  Image Name:           ${IMAGE_NAME}"
echo "  Version:              ${VERSION}"
echo "  Full Image:           ${FULL_IMAGE_NAME}"
echo "  Latest Tag:           ${LATEST_IMAGE_NAME}"
echo "  Skip Tests:           ${SKIP_TESTS}"
echo "  Skip Push:            ${SKIP_PUSH}"
echo ""

read -p "Vuoi procedere con il build? (y/n) " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    log_warning "Build cancellato dall'utente"
    exit 0
fi

log_info "Building Valkey UI image..."

if [ "$SKIP_TESTS" = true ]; then
    log_warning "Skipping tests during build (--skip-tests attivo)"
    MAVEN_ARGS="-DskipTests"
else
    log_info "Running tests during build"
    MAVEN_ARGS=""
fi

docker build \
    -t "${FULL_IMAGE_NAME}" \
    -t "${LATEST_IMAGE_NAME}" \
    -f Dockerfile \
    .

log_success "Valkey UI image buildata: ${FULL_IMAGE_NAME}"

if [ "$SKIP_PUSH" = false ]; then
    echo ""
    log_info "Pushing image to registry..."

    log_info "Pushing version tag..."
    docker push "${FULL_IMAGE_NAME}"
    log_success "Pushed: ${FULL_IMAGE_NAME}"

    log_info "Pushing latest tag..."
    docker push "${LATEST_IMAGE_NAME}"
    log_success "Pushed: ${LATEST_IMAGE_NAME}"
else
    log_warning "Push skippato (--skip-push attivo)"
fi

echo ""
echo "╔════════════════════════════════════════════════════════╗"
echo "║                   Build Completato                     ║"
echo "╚════════════════════════════════════════════════════════╝"
echo ""
log_success "Immagine creata con successo!"
echo ""

if [ "$SKIP_PUSH" = false ]; then
    log_info "Immagini disponibili sul registry:"
    echo "  ${FULL_IMAGE_NAME}"
    echo "  ${LATEST_IMAGE_NAME}"
    echo ""
    log_info "Per deployare, usa:"
    echo "  docker pull ${FULL_IMAGE_NAME}"
    echo "  docker run -d --name valkey-ui -p 8096:8080 ${FULL_IMAGE_NAME}"
else
    log_info "Per pushare le immagini, esegui:"
    echo "  docker push ${FULL_IMAGE_NAME}"
    echo "  docker push ${LATEST_IMAGE_NAME}"
fi
