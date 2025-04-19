#!/usr/bin/env bash

set -e

moduleName="systems.glam.look"
mainClass="systems.glam.look.http.LookupTableWebService"

dockerImageName="glam-systems/look:latest"
dockerRunFlags="--detach --name look --memory 12g --publish 4242:4242"
jvmArgs="-server -XX:+UseCompressedOops -XX:+UseShenandoahGC -Xms8G -Xmx11G -XX:+UnlockExperimentalVMOptions -XX:+TrustFinalNonStaticFields"
logLevel="INFO"
configDirectory="$(pwd)/.config"
configFileName=""

for arg in "$@"
do
  if [[ "$arg" =~ ^--.* ]]; then
    key="${arg%%=*}"
    key="${key##*--}"
    val="${arg#*=}"

    case "$key" in
      l | log)
          case "$val" in
            INFO|WARN|DEBUG) logLevel="$val";;
            *)
              printf "'%slog=[INFO|WARN|DEBUG]' not '%s'.\n" "--" "$arg";
              exit 2;
            ;;
          esac
        ;;

      mc | mainClass) mainClass="$val";;
      mn | moduleName) moduleName="$val";;

      drf | dockerRunFlags) dockerRunFlags="$val";;
      jvm | jvmArgs) jvmArgs="$val";;

      cd | configDirectory) configDirectory="$val";;
      cfn | configFileName) configFileName="$val";;

      *)
          printf "Unsupported flag '%s' [key=%s] [val=%s].\n" "$arg" "$key" "$val";
          exit 1;
        ;;
    esac
  else
    printf "Unhandled argument '%s', all flags must begin with '%s'.\n" "$arg" "--";
    exit 1;
  fi
done

IFS=' ' read -r -a dockerRunFlagArray <<< "$dockerRunFlags"
IFS=' ' read -r -a jvmArgsArray <<< "$jvmArgs"

docker stop look
docker rm look

set -x
docker run "${dockerRunFlagArray[@]}" \
  --mount type=bind,source="$configDirectory",target=/look/.config/,readonly \
  --mount source=look_table_cache,target=/look/.look/table_cache \
    "$dockerImageName" \
      "${jvmArgsArray[@]}" \
      "-D$moduleName.logLevel=$logLevel" \
      "-D$moduleName.config=/look/.config/$configFileName" \
      -m "$moduleName/$mainClass"
