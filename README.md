# ServerRestarterJava
ServerRestarterJava is a tool written in Java for automatically restarting servers either via ssh or if they are unresponsive via turning 
off their power in an autonomous environment. The servers must therefor be connected to a PDU accessable via the SNMP protocol.

## Requirements
**Minimum Java Version**: 8

## Usage
1. For security and convenience reasons the ssh username and password have to be hardcoded in the application before compiling.
2. The compiled jar file has to be executed within a directory containing a config file. It will be created when running the program the 
first time and has to be configured as specified below.

## Commands
| Command | Description |
| ------- | ----------- |
| `activate` | Activates the server status checker. |
| `deactivate` | Deactivates the server status checker. |
| `list` | Prints a list of all loaded servers of the config file. |
| `status <String:ServerID>` | Prints out the status and power usage of the server with the given ID. |
| `restart [-h] <String:ServerID>` | Restarts the server with the given ID. Add -h to hard restart it (via its power supply). |
| `reload` | Reloads the config file. |
| `help` | Shows a helpful list of available commands. |
| `quit` | Quits the program. |
The first letter of each command can be used as an alias for the whole command.

## Configuration
Specify the following parameters in the created *config.txt* file:
- **checkIntervalInSeconds**: The time in seconds to be waited within server activity checks.
- **servers**: An array of servers each containing the following values:
  - *id*: The name of the server, can be chosen arbitrarily.
  - *ip*: The ip of the server.
  - *sshKeyFilePath*: The path to the ssh key file. Leave blank if unused.
  - *pduIp*: The ip of the pdu the server is connected to.
  - *pduIndex*: The index of the pdu within its mesh (if they are interconnected).
  - *pduOutletNumber*: The number of the outlet the server is connected to.
  - *triggerMinimumPower*: The minimum power usage in Watt that the server draws if doesn't have to be restarted.
  - *controlActive*: Whether the configured server should be included in the activity check (for maintenance purposes).
    
##### Example configuration file:
```
{
"checkIntervalInSeconds": 300,
"servers": [
               {
                 "id": "Server001",
                 "ip": "127.0.0.1",
                 "sshKeyFilePath": "~/.ssh/id_rsa",
                 "pduIp": "127.0.0.2",
                 "pduIndex": 1,
                 "pduOutletNumber": 1,
                 "triggerMinimumPower": 500,
                 "controlActive": true
               }
           ]
}
```
  
