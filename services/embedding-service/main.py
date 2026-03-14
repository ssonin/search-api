import asyncio
import base64
import json
import logging
import os
from contextlib import asynccontextmanager

import asyncpg
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
INPUT_TOPIC = "searchapi.message"
OUTPUT_TOPIC = "searchapi.document.embedding"
CONSUMER_GROUP = "embedding-service"

logger.info("Loading sentence-transformer model...")
model = SentenceTransformer("all-MiniLM-L6-v2")
logger.info("Model loaded successfully")


async def embedding_consumer():
  pg_pool = await asyncpg.create_pool(
    host=os.getenv("PGHOST", "postgres"),
    port=int(os.getenv("PGPORT", "5432")),
    database=os.getenv("PGDATABASE", "search_api"),
    user=os.getenv("PGUSER", "search_api"),
    password=os.getenv("PGPASSWORD", "search_api"),
  )
  consumer = AIOKafkaConsumer(
    INPUT_TOPIC,
    bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
    group_id=CONSUMER_GROUP,
    auto_offset_reset="earliest",
    value_deserializer=lambda v: json.loads(v),
  )
  producer = AIOKafkaProducer(
    bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
    value_serializer=lambda v: json.dumps(v).encode(),
  )
  await consumer.start()
  await producer.start()
  try:
    async for msg in consumer:
      try:
        payload = msg.value.get("payload", {})
        if payload.get("op") != "m":
          continue
        message = payload.get("message", {})
        if message.get("prefix") != "document.created":
          continue
        doc_id = base64.b64decode(message["content"]).decode("utf-8")
        content = await pg_pool.fetchval(
          "SELECT content FROM documents WHERE id = $1::uuid", doc_id
        )
        if content is None:
          logger.warning("Document %s not found, skipping", doc_id)
          continue
        [embedding] = model.encode([content]).tolist()
        await producer.send(OUTPUT_TOPIC, {"documentId": doc_id, "embedding": embedding})
        logger.info("Published embedding for document %s", doc_id)
      except Exception:
        logger.exception("Failed to process message, skipping")
  finally:
    await consumer.stop()
    await producer.stop()
    await pg_pool.close()

@asynccontextmanager
async def lifespan(app: FastAPI):
  task = asyncio.create_task(embedding_consumer())
  yield
  task.cancel()
  try:
    await task
  except asyncio.CancelledError:
    pass

app = FastAPI(
  title="Embedding Service",
  description="Generates text embeddings using all-MiniLM-L6-v2",
  lifespan=lifespan,
)

class EmbeddingRequest(BaseModel):
  texts: list[str] = Field(..., min_length=1, max_length=100)

class EmbeddingResponse(BaseModel):
  embeddings: list[list[float]]

@app.get("/health")
def health_check():
  return {"status": "healthy"}

@app.post("/embeddings", response_model=EmbeddingResponse)
def get_embeddings(request: EmbeddingRequest):
  if not request.texts:
    raise HTTPException(status_code=400, detail="texts cannot be empty")

  try:
    embeddings = model.encode(request.texts)
    return EmbeddingResponse(embeddings=embeddings.tolist())
  except Exception as e:
    logger.exception("Failed to generate embeddings")
    raise HTTPException(status_code=500, detail=str(e))
