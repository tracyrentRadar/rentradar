# The RentRadar inference service.
#
# Two things ship alongside the code and both are deliberate. The feature spec,
# because serving must encode a property exactly as training did. And the
# training feature matrix, because the fraud autoencoder scores a listing
# against its nearest comparables from the training fold, so those comparables
# are part of the model rather than an input to it.
#
# The raw corpus is not here. It holds letting agent names and phone numbers.

FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TF_CPP_MIN_LOG_LEVEL=3 \
    TF_ENABLE_ONEDNN_OPTS=0

WORKDIR /app

# Dependencies first, so a code change does not re-download TensorFlow.
COPY service/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY rentradar/ ./rentradar/
COPY service/ ./service/
COPY data/features/ ./data/features/
COPY artifacts/models/ ./artifacts/models/

EXPOSE 8000

# Models load at startup and TensorFlow is slow to wake, hence the long grace
# period before the first check counts against it.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD python -c "import urllib.request, sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4).status == 200 else 1)"

CMD ["uvicorn", "service.main:app", "--host", "0.0.0.0", "--port", "8000"]