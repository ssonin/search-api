package ssonin.searchapi.repository;

interface SqlQueries {

  static String insertClient() {
    return """
      INSERT INTO clients (id, first_name, last_name, email, description)
      VALUES ($1, $2, $3, $4, $5)
      RETURNING id, created_at, first_name, last_name, email, description;
      """;
  }

  static String selectClient() {
    return """
      SELECT id, created_at, first_name, last_name, email, description
      FROM clients
      WHERE id = $1;
      """;
  }

  static String selectClientForUpdate() {
    return """
      SELECT 1
      FROM clients
      WHERE id = $1
      FOR UPDATE;
      """;
  }

  static String insertDocument() {
    return """
      INSERT INTO documents (id, client_id, title, content, embedding)
      VALUES ($1, $2, $3, $4, $5::vector)
      RETURNING id, created_at, client_id, title, content;
      """;
  }

  static String searchClients() {
    return """
      SELECT
        'client' AS type,
        id,
        created_at,
        first_name,
        last_name,
        email,
        description,
        ts_rank(search, query) AS rank
      FROM clients, plainto_tsquery('english', $1) query
      WHERE search @@ query
      ORDER BY rank DESC;
      """;
  }

  static String searchDocuments() {
    return """
      WITH fts_results AS (
        SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank(search, plainto_tsquery('english', $1)) DESC) AS rank_pos
        FROM documents
        WHERE search @@ plainto_tsquery('english', $1)
        LIMIT 20
      ),
      vector_results AS (
        SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> $2::vector) AS rank_pos
        FROM documents
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> $2::vector
        LIMIT 20
      ),
      combined AS (
        SELECT
          id,
          COALESCE(1.0 / (60 + fts.rank_pos), 0) + COALESCE(1.0 / (60 + vec.rank_pos), 0) AS rrf_score
        FROM fts_results fts
        FULL OUTER JOIN vector_results vec USING (id)
      )
    SELECT
      'document' AS type,
      d.id,
      d.created_at,
      d.client_id,
      d.title,
      d.content,
      c.rrf_score as rank
    FROM combined c
    JOIN documents d ON d.id = c.id
    ORDER BY c.rrf_score DESC
    LIMIT 20;
    """;
  }
}
