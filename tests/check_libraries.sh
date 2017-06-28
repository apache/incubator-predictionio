#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Go to PredictionIO directory
FWDIR="$(cd "`dirname "$0"`"/..; pwd)"
mkdir -p ${FWDIR}/lib
cd ${FWDIR}

echo "Check library dependencies..."

# Generate license report
sbt/sbt clean
sbt/sbt dumpLicenseReport

sbt/sbt storage/clean
sbt/sbt storage/dumpLicenseReport

# Clean up
REPORT_DIR="${FWDIR}/test-reports"
GATHERED_FILE="${REPORT_DIR}/licences-gathered.csv"
FILTERED_FILE="${REPORT_DIR}/licences-filtered.csv"
ERROR_FILE="${REPORT_DIR}/licences-errors.csv"
mkdir -p ${REPORT_DIR}

rm -f ${GATHERED_FILE}
rm -f ${FILTERED_FILE}
rm -f ${ERROR_FILE}

# Gather and filter reports
find . -name "*-licenses.csv" -exec cat {} >> ${GATHERED_FILE} \;
cat ${GATHERED_FILE} | sort | uniq | grep -v "Category,License,Dependency,Notes" | \
  grep -v Apache | grep -v ASL | \
  grep -v "org.apache" | grep -v "commons-" | \
  grep -v "org.codehaus.jettison" | \
  grep -v predictionio > ${FILTERED_FILE}

# Check undocumented
cat ${FILTERED_FILE} | while read LINE
do
  LIBRARY=`echo ${LINE} | cut -d ',' -f 3`
  grep -q "$LIBRARY" LICENSE.txt
  if [ $? -ne 0 ]; then
    echo -e "\033[0;31m[error]\033[0;39m Undocumented dependency: $LINE"
    echo $LINE >> ${ERROR_FILE}
  fi
done

if [ -f ${ERROR_FILE} ]; then
  echo "Library checks failed."
  exit 1
else 
  echo "Library checks passed."
  exit 0
fi

