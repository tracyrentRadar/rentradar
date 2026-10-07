# RentRadar

A rental price intelligence platform for Accra. It is not a listings marketplace.
Given the details of a rental property it returns three things: a fair price
estimate with a confidence interval, a fraud risk assessment, and a market
trend figure.

BSc Information Technology final year project, Valley View University.

## Repository layout

| Folder | Contents |
|---|---|
| `backend/` | Spring Boot 3.5 API gateway, Java, MongoDB |
| `ml/` | Python data collection, feature pipeline and models |
| `android/` | Android client, Java with Fragments and Material 3 |
| `docs/` | Collection evidence and project documentation |

## Running it

### Backend

Needs `backend/src/main/resources/application-dev.yml`, which is not in the
repository because it holds the database connection string. Copy the example
beside it, fill in your own credentials, then from the `backend` folder run
`mvnw spring-boot:run`. Health check at `http://localhost:8080/api/v1/health`.

### ML pipeline

From the `ml` folder, build the features with
`py -m rentradar.features.build`, score the baselines with
`py -m rentradar.models.baseline`, and run the forecaster with
`py -m rentradar.models.forecast_lstm fit --until 2026-09`.

### Android

Open the `android/` folder in Android Studio and run the app.

## What is not in this repository

The collected corpus under `ml/data/` and the trained model binaries are
excluded. The corpus holds letting agent names and phone numbers taken from
public listings, so it is not distributed. Model results in JSON are kept,
under `ml/artifacts/models/`, because every figure quoted in the write up
comes from them.

`docs/collection-evidence/` holds the robots.txt snapshot and the listing URL
list captured before collection began, as evidence that the source sites'
terms were checked.

## Results so far

| Task | Model | Score |
|---|---|---|
| Price estimate | Gradient boosting | R squared 0.706 |
| Price estimate | Stacked LSTM | R squared 0.409, not distinguishable at p 0.177 |
| Fraud detection | LSTM autoencoder | price gap correlation -0.150, no labels yet so no F1 |
| Forecast | Six month moving average | MAE 2,297 GHS, MAPE 11.3% |
| Forecast | Sequence to sequence LSTM | MAE 3,385 GHS, MAPE 16.2% |

Two of the three deep models were beaten by simpler methods on this corpus.
Those are reported as measured, with paired significance tests, rather than
tuned until they won.

## Status

Backend, data collection, feature pipeline and all three model families are
built. The Python inference service and its integration with the backend are
not, so the API currently serves stub predictions. There is no test suite yet.