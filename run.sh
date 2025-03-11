# Download NoiseModelling versions and extract it
mkdir -p input
if [ ! -f input/NoiseModelling_3.4.4.zip ]; then
  curl -L https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v3.4.4/NoiseModelling_3.4.4.zip -o input/NoiseModelling_3.4.4.zip
  unzip input/NoiseModelling_3.4.4.zip -d input/
fi
