FROM --platform=$BUILDPLATFORM golang:1 AS build
ARG TARGETOS=linux
ARG TARGETARCH=amd64
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags="-s -w" -o /out/beacon-server ./cmd/beacon

FROM alpine:3
RUN mkdir -p /data && chown 65532:65532 /data && chmod 700 /data
COPY --from=build /out/beacon-server /usr/local/bin/beacon-server
USER 65532:65532
WORKDIR /data
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/beacon-server", "-db", "/data/beacon.db"]
CMD ["-listen", "0.0.0.0:8080"]
