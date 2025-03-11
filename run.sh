# Download NoiseModelling versions and extract it
mkdir -p input
mkdir -p output
if [ ! -f input/NoiseModelling_3.4.4.zip ]; then
  curl -L https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v3.4.4/NoiseModelling_3.4.4.zip -o input/NoiseModelling_3.4.4.zip
  unzip input/NoiseModelling_3.4.4.zip -d input/
fi

# Run simulation
mkdir -p output/scriptv5.0
input/scriptrunnerv5.0/bin/wps_scripts -w output/scriptv5.0/ -s nm_v5.0/src/main/groovy/runscriptV5.0.groovy


