
-- Table to store secure payment transactions
CREATE SEQUENCE IF NOT EXISTS transactions_id_seq_custom;
CREATE TABLE public.transactions (
  id bigint NOT NULL DEFAULT nextval('transactions_id_seq_custom'::regclass), -- Use a distinct sequence or UUID ideally
  user_id bigint NOT NULL,
  course_id bigint NOT NULL,
  amount numeric(12, 2) NOT NULL,
  currency text DEFAULT 'COP',
  status text NOT NULL DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED, FAILED
  payment_method text DEFAULT 'PSE',
  external_reference text, -- Reference ID from PayU/PSE
  transaction_date timestamp with time zone DEFAULT now(),
  updated_at timestamp with time zone DEFAULT now(),
  encrypted_metadata text, -- For additional secure data storage if required by compliance
  CONSTRAINT transactions_pkey PRIMARY KEY (id),
  CONSTRAINT fk_transactions_user FOREIGN KEY (user_id) REFERENCES public.usuarios(id),
  CONSTRAINT fk_transactions_course FOREIGN KEY (course_id) REFERENCES public.courses(id)
);

-- Index for fast lookup by external reference (webhook handling)
CREATE INDEX idx_transactions_external_ref ON public.transactions(external_reference);
CREATE INDEX idx_transactions_user_course ON public.transactions(user_id, course_id);

-- Audit fields update
ALTER TABLE public.transactions 
ADD COLUMN IF NOT EXISTS client_ip text,
ADD COLUMN IF NOT EXISTS user_agent text,
ADD COLUMN IF NOT EXISTS provider_response jsonb,
ADD COLUMN IF NOT EXISTS audit_log jsonb DEFAULT '[]'::jsonb;

COMMENT ON COLUMN public.transactions.client_ip IS 'IP address of the user initiating the transaction';
COMMENT ON COLUMN public.transactions.user_agent IS 'Browser/Device user agent string';
COMMENT ON COLUMN public.transactions.provider_response IS 'Raw JSON response from the payment provider (Wompi/PayU)';
COMMENT ON COLUMN public.transactions.audit_log IS 'Array of audit events for this transaction';


-- Foreign Keys for reinforcement_question_history
-- First, ensure types match the parent tables (bigint)
ALTER TABLE public.reinforcement_question_history 
  ALTER COLUMN user_id TYPE bigint,
  ALTER COLUMN course_id TYPE bigint;

-- Add Constraints
ALTER TABLE public.reinforcement_question_history
  ADD CONSTRAINT fk_reinforcement_history_user FOREIGN KEY (user_id) REFERENCES public.usuarios(id),
  ADD CONSTRAINT fk_reinforcement_history_course FOREIGN KEY (course_id) REFERENCES public.courses(id),
  ADD CONSTRAINT fk_reinforcement_history_topic FOREIGN KEY (topic_id) REFERENCES public.topics(id),
  ADD CONSTRAINT fk_reinforcement_history_task FOREIGN KEY (task_id) REFERENCES public.tasks(id);

