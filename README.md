\# RentRadar



A rental price intelligence platform for Accra. It is not a listings marketplace.

Given the details of a rental property it returns three things: a fair price

estimate with a confidence interval, a fraud risk assessment, and a market

trend figure.



BSc Information Technology final year project, Valley View University.



\## Repository layout



| Folder | Contents |

|---|---|

| `backend/` | Spring Boot 3.5 API gateway, Java, MongoDB |

| `ml/` | Python data collection, feature pipeline and models |

| `android/` | Android client, Java with Fragments and Material 3 |

| `docs/` | Collection evidence and project documentation |



\## Running it



\### Backend



Needs `backend/src/main/resources/application-dev.yml`, which is not in the

repository because it holds the database connection string. Copy the example

and fill it in:

