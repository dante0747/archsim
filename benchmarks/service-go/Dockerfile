FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /bench .

FROM alpine:3.22
COPY --from=build /bench /bench
USER nobody
ENTRYPOINT ["/bench"]
