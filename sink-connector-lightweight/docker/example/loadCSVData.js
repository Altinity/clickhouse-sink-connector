/*
 * This Node.js application is licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const fs = require('fs');
const readline = require('readline');
const mysql = require('mysql');

function printHelp() {
    console.log(`
Usage: node loadCSVData.js [options]

Options:
  --hostname     MySQL server hostname (default: localhost)
  --port         MySQL server port (default: 3306)
  --databaseName Database name (default: test)
  --username     MySQL username (default: root)
  --password     MySQL password (default: root)
  --csvFile      CSV file name (default: "5000_Sales_Records.csv")
  --help         Print this help text
`);
}

// Check for --help argument
if (process.argv.includes('--help')) {
    printHelp();
    process.exit(0);
}

const defaultHost = 'localhost';
const defaultPort = 3306;
const defaultDatabaseName = 'test';
const defaultUsername = 'root';
const defaultPassword = 'root';
const defaultCSVFile = '5000_Sales_Records.csv';

// Parse command line arguments
const args=process.argv.slice(2);
let host = defaultHost;
let port = defaultPort;
let databaseName = defaultDatabaseName;
let username = defaultUsername;
let password = defaultPassword;
let csvFile = defaultCSVFile;

args.forEach((arg, index) => {
    if (arg.startsWith('--hostname')) {
        host = args[index + 1] || defaultHostname;
    } else if (arg.startsWith('--port')) {
        port = args[index + 1] || defaultPort;
    } else if (arg.startsWith('--databaseName')) {
        databaseName = args[index + 1] || defaultDatabaseName;
    } else if (arg.startsWith('--username')) {
        username = args[index + 1] || defaultUsername;
    } else if (arg.startsWith('--password')) {
        password = args[index + 1] || defaultPassword;
    } else if (arg.startsWith('--csvFile')) {
        csvFile = args[index + 1] || defaultCSVFile;
    }
});

// MySQL database configuration
const connection = mysql.createConnection({
    host: host,
    port: port,
    user: username,
    password: password,
    database: databaseName
});

// SQL statement to create the table
const createTableSQL = `
    CREATE TABLE IF NOT EXISTS sales_records (
        region VARCHAR(50),
        country VARCHAR(50),
        item_type VARCHAR(20),
        sales_channel VARCHAR(20),
        order_priority VARCHAR(5),
        order_date DATE,
        order_id BIGINT,
        ship_date DATE,
        units_sold INT,
        unit_price FLOAT,
        unit_cost FLOAT,
        total_revenue FLOAT,
        total_cost FLOAT,
        total_profit FLOAT,
        PRIMARY KEY(order_id)
    )
`;

// Function to escape single quotes in data
function escapeSingleQuotes(data) {
    return data.replace(/'/g, "''");
}

// Function to check if a string is in mm/dd/yyyy format
// Month and day values may be one or two digits
function isValidDateFormat(dateString) {
    const regex = /^\d{1,2}\/\d{1,2}\/\d{4}$/;
    return regex.test(dateString);
}

// Function to convert date from mm/dd/yyyy to yyyy-mm-dd format
function convertDateFormat(dateString) {
    const [month, day, year] = dateString.split('/');
    return `${year}-${month}-${day}`;
}

// Connect to MySQL database and create table
connection.connect((err) => {
    if (err) {
        console.error('Error connecting to MySQL:', err);
        return;
    }
    console.log('Connected to MySQL database.');

    // Create the table
    connection.query(createTableSQL, (err, results) => {
        if (err) {
            console.error('Error creating table:', err);
            return;
        }
        console.log('Table created successfully.');

        // Read CSV file
        const fileStream = fs.createReadStream(csvFile);
        const rl = readline.createInterface({
            input: fileStream,
            crlfDelay: Infinity
        });

        let firstLineSkipped = false;
        rl.on('line', (line) => {
            if (!firstLineSkipped) {
                firstLineSkipped = true;
                return; // Skip the first line
            }
            const values = line.split(',');
            const escapedValues = values.map(escapeSingleQuotes);
            const dateCorrectedValues = escapedValues.map(value => {
		        if (isValidDateFormat(value)) {
                    return convertDateFormat(value);
	        	} else {
                    return value;
		        }
            });
            const insertQuery = `INSERT INTO sales_records VALUES ('${dateCorrectedValues.join("', '")}')`;
            connection.query(insertQuery, (err, results) => {
                if (err) {
                    console.error('Error executing INSERT query:', err);
                }
            });
        });

        rl.on('close', () => {
	    console.log('All lines processed.');
            connection.end(); // Close the database connection
        });
    });
});
