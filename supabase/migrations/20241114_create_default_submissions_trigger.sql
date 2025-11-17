-- Migración: Crear trigger para asignar automáticamente calificación 0 a estudiantes inscritos cuando se crea una tarea
-- Fecha: 2024-11-14
-- Descripción: Cuando se inserta una nueva tarea, se crean automáticamente submissions con grade=0 para todos los estudiantes inscritos

-- Función para crear submissions por defecto
CREATE OR REPLACE FUNCTION create_default_submissions_for_task()
RETURNS TRIGGER AS $$
DECLARE
    enrolled_student RECORD;
    course_id_from_topic BIGINT;
BEGIN
    -- Obtener el courseId desde el topicId de la tarea
    SELECT course_id INTO course_id_from_topic
    FROM topics
    WHERE id = NEW.topic_id;
    
    -- Si no se encuentra el curso, salir
    IF course_id_from_topic IS NULL THEN
        RAISE NOTICE 'No course found for topic_id: %', NEW.topic_id;
        RETURN NEW;
    END IF;
    
    -- Para cada estudiante inscrito en el curso
    FOR enrolled_student IN 
        SELECT usuario_estudiante 
        FROM progreso_estudiante 
        WHERE curso_id = course_id_from_topic
    LOOP
        -- Verificar si ya existe una submission para este estudiante y tarea
        IF NOT EXISTS (
            SELECT 1 
            FROM task_submissions 
            WHERE task_id = NEW.id 
            AND student_username = enrolled_student.usuario_estudiante
        ) THEN
            -- Crear submission con calificación 0 por defecto
            INSERT INTO task_submissions (
                task_id,
                student_username,
                submission_date,
                file_uri,
                grade,
                feedback,
                graded_date
            ) VALUES (
                NEW.id,
                enrolled_student.usuario_estudiante,
                EXTRACT(EPOCH FROM NOW()) * 1000, -- timestamp en milisegundos
                '', -- sin archivo adjunto inicialmente
                0.0, -- calificación inicial de 0
                'Tarea pendiente de entrega',
                NULL -- sin fecha de calificación aún
            );
            
            RAISE NOTICE 'Created default submission for student: % on task: %', 
                enrolled_student.usuario_estudiante, NEW.id;
        END IF;
    END LOOP;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Crear el trigger que se ejecuta después de insertar una nueva tarea
DROP TRIGGER IF EXISTS trigger_create_default_submissions ON tasks;

CREATE TRIGGER trigger_create_default_submissions
AFTER INSERT ON tasks
FOR EACH ROW
EXECUTE FUNCTION create_default_submissions_for_task();

-- Comentarios sobre el trigger
COMMENT ON FUNCTION create_default_submissions_for_task() IS 
'Crea automáticamente submissions con calificación 0 para todos los estudiantes inscritos cuando se crea una nueva tarea';

COMMENT ON TRIGGER trigger_create_default_submissions ON tasks IS 
'Trigger que ejecuta create_default_submissions_for_task() después de insertar una tarea';

-- Función auxiliar para actualizar el progreso de un estudiante después de crear una tarea
CREATE OR REPLACE FUNCTION update_student_progress_after_task_creation()
RETURNS TRIGGER AS $$
DECLARE
    student_username TEXT;
    course_id_val BIGINT;
    total_grade NUMERIC;
    task_count INTEGER;
    new_average NUMERIC;
    total_tasks INTEGER;
    completed_tasks INTEGER;
    progress_percentage REAL;
BEGIN
    -- Obtener username y courseId
    student_username := NEW.student_username;
    
    -- Obtener el courseId desde el taskId
    SELECT t.course_id INTO course_id_val
    FROM tasks tk
    JOIN topics t ON tk.topic_id = t.id
    WHERE tk.id = NEW.task_id;
    
    IF course_id_val IS NULL THEN
        RETURN NEW;
    END IF;
    
    -- Calcular total de tareas en el curso
    SELECT COUNT(*) INTO total_tasks
    FROM tasks tk
    JOIN topics t ON tk.topic_id = t.id
    WHERE t.course_id = course_id_val;
    
    -- Calcular tareas completadas (con grade > 0)
    SELECT COUNT(*) INTO completed_tasks
    FROM task_submissions ts
    JOIN tasks tk ON ts.task_id = tk.id
    JOIN topics t ON tk.topic_id = t.id
    WHERE ts.student_username = student_username
    AND t.course_id = course_id_val
    AND ts.grade > 0;
    
    -- Calcular porcentaje de progreso
    IF total_tasks > 0 THEN
        progress_percentage := (completed_tasks::REAL / total_tasks::REAL) * 100;
    ELSE
        progress_percentage := 0;
    END IF;
    
    -- Calcular promedio actualizado
    SELECT 
        COALESCE(SUM(ts.grade), 0),
        COUNT(*)
    INTO total_grade, task_count
    FROM task_submissions ts
    JOIN tasks tk ON ts.task_id = tk.id
    JOIN topics t ON tk.topic_id = t.id
    WHERE ts.student_username = student_username
    AND t.course_id = course_id_val;
    
    -- Calcular nuevo promedio
    IF task_count > 0 THEN
        new_average := total_grade / task_count;
    ELSE
        new_average := 0;
    END IF;
    
    -- Actualizar progreso_estudiante con todos los campos
    UPDATE progreso_estudiante
    SET 
        tareas_totales = total_tasks,
        tareas_completadas = completed_tasks,
        porcentaje_progreso = progress_percentage,
        promedio = new_average,
        calificacion_ponderada = new_average,
        ultima_calculada_en = NOW()
    WHERE usuario_estudiante = student_username
    AND curso_id = course_id_val;
    
    RAISE NOTICE 'Updated progress for student: % in course: %', student_username, course_id_val;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Crear trigger para actualizar progreso cuando se inserta una submission
DROP TRIGGER IF EXISTS trigger_update_progress_on_submission_insert ON task_submissions;

CREATE TRIGGER trigger_update_progress_on_submission_insert
AFTER INSERT ON task_submissions
FOR EACH ROW
EXECUTE FUNCTION update_student_progress_after_task_creation();

-- Crear trigger para actualizar progreso cuando se actualiza una submission (cambio de calificación)
DROP TRIGGER IF EXISTS trigger_update_progress_on_submission_update ON task_submissions;

CREATE TRIGGER trigger_update_progress_on_submission_update
AFTER UPDATE OF grade ON task_submissions
FOR EACH ROW
WHEN (OLD.grade IS DISTINCT FROM NEW.grade)
EXECUTE FUNCTION update_student_progress_after_task_creation();

COMMENT ON FUNCTION update_student_progress_after_task_creation() IS 
'Recalcula el promedio del estudiante después de crear o actualizar una submission';

-- Función adicional: Actualizar tareas_totales para todos los estudiantes cuando se crea una nueva tarea
CREATE OR REPLACE FUNCTION update_all_students_task_count_on_task_insert()
RETURNS TRIGGER AS $$
DECLARE
    course_id_from_topic BIGINT;
    enrolled_student RECORD;
    total_tasks_count INTEGER;
    student_completed_tasks INTEGER;
    student_progress_pct REAL;
BEGIN
    -- Obtener el courseId desde el topicId de la tarea
    SELECT course_id INTO course_id_from_topic
    FROM topics
    WHERE id = NEW.topic_id;
    
    IF course_id_from_topic IS NULL THEN
        RAISE NOTICE 'No course found for topic_id: %', NEW.topic_id;
        RETURN NEW;
    END IF;
    
    -- Calcular total de tareas en el curso
    SELECT COUNT(*) INTO total_tasks_count
    FROM tasks tk
    JOIN topics t ON tk.topic_id = t.id
    WHERE t.course_id = course_id_from_topic;
    
    -- Actualizar tareas_totales para cada estudiante inscrito
    FOR enrolled_student IN 
        SELECT usuario_estudiante 
        FROM progreso_estudiante 
        WHERE curso_id = course_id_from_topic
    LOOP
        -- Calcular tareas completadas del estudiante
        SELECT COUNT(*) INTO student_completed_tasks
        FROM task_submissions ts
        JOIN tasks tk ON ts.task_id = tk.id
        JOIN topics t ON tk.topic_id = t.id
        WHERE ts.student_username = enrolled_student.usuario_estudiante
        AND t.course_id = course_id_from_topic
        AND ts.grade > 0;
        
        -- Calcular porcentaje de progreso
        IF total_tasks_count > 0 THEN
            student_progress_pct := (student_completed_tasks::REAL / total_tasks_count::REAL) * 100;
        ELSE
            student_progress_pct := 0;
        END IF;
        
        -- Actualizar progreso del estudiante
        UPDATE progreso_estudiante
        SET 
            tareas_totales = total_tasks_count,
            tareas_completadas = student_completed_tasks,
            porcentaje_progreso = student_progress_pct,
            ultima_calculada_en = NOW()
        WHERE usuario_estudiante = enrolled_student.usuario_estudiante
        AND curso_id = course_id_from_topic;
        
        RAISE NOTICE 'Updated task count for student: %', enrolled_student.usuario_estudiante;
    END LOOP;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger que actualiza el conteo de tareas cuando se inserta una nueva tarea
DROP TRIGGER IF EXISTS trigger_update_task_count_on_insert ON tasks;

CREATE TRIGGER trigger_update_task_count_on_insert
AFTER INSERT ON tasks
FOR EACH ROW
EXECUTE FUNCTION update_all_students_task_count_on_task_insert();

COMMENT ON FUNCTION update_all_students_task_count_on_task_insert() IS
'Actualiza tareas_totales, tareas_completadas y porcentaje_progreso para todos los estudiantes cuando se crea una tarea';

COMMENT ON TRIGGER trigger_update_task_count_on_insert ON tasks IS
'Trigger que actualiza el conteo de tareas para todos los estudiantes inscritos cuando se crea una nueva tarea';

-- Función para actualizar progreso cuando se elimina una tarea
CREATE OR REPLACE FUNCTION update_all_students_task_count_on_task_delete()
RETURNS TRIGGER AS $$
DECLARE
    course_id_from_topic BIGINT;
    enrolled_student RECORD;
    total_tasks_count INTEGER;
    student_completed_tasks INTEGER;
    student_progress_pct REAL;
    student_avg_grade REAL;
BEGIN
    -- Obtener el courseId desde el topicId de la tarea eliminada
    SELECT course_id INTO course_id_from_topic
    FROM topics
    WHERE id = OLD.topic_id;
    
    IF course_id_from_topic IS NULL THEN
        RAISE NOTICE 'No course found for topic_id: %', OLD.topic_id;
        RETURN OLD;
    END IF;progreso_estudiante
    
    -- Calcular total de tareas restantes en el curso (después de la eliminación)
    SELECT COUNT(*) INTO total_tasks_count
    FROM tasks tk
    JOIN topics t ON tk.topic_id = t.id
    WHERE t.course_id = course_id_from_topic
    AND tk.id != OLD.id; -- Excluir la tarea que se está eliminando
    
    -- Actualizar progreso para cada estudiante inscrito
    FOR enrolled_student IN 
        SELECT usuario_estudiante 
        FROM progreso_estudiante 
        WHERE curso_id = course_id_from_topic
    LOOP
        -- Calcular tareas completadas del estudiante (sin la tarea eliminada)
        SELECT COUNT(*) INTO student_completed_tasks
        FROM task_submissions ts
        JOIN tasks tk ON ts.task_id = tk.id
        JOIN topics t ON tk.topic_id = t.id
        WHERE ts.student_username = enrolled_student.usuario_estudiante
        AND t.course_id = course_id_from_topic
        AND tk.id != OLD.id
        AND ts.grade > 0;
        
        -- Calcular promedio actualizado (sin la tarea eliminada)
        SELECT COALESCE(AVG(ts.grade), 0) INTO student_avg_grade
        FROM task_submissions ts
        JOIN tasks tk ON ts.task_id = tk.id
        JOIN topics t ON tk.topic_id = t.id
        WHERE ts.student_username = enrolled_student.usuario_estudiante
        AND t.course_id = course_id_from_topic
        AND tk.id != OLD.id;
        
        -- Calcular porcentaje de progreso
        IF total_tasks_count > 0 THEN
            student_progress_pct := (student_completed_tasks::REAL / total_tasks_count::REAL) * 100;
        ELSE
            student_progress_pct := 0;
        END IF;
        
        -- Actualizar progreso del estudiante
        UPDATE progreso_estudiante
        SET 
            tareas_totales = total_tasks_count,
            tareas_completadas = student_completed_tasks,
            porcentaje_progreso = student_progress_pct,
            promedio = student_avg_grade,
            calificacion_ponderada = student_avg_grade,
            ultima_calculada_en = NOW()
        WHERE usuario_estudiante = enrolled_student.usuario_estudiante
        AND curso_id = course_id_from_topic;
        
        RAISE NOTICE 'Updated progress after task deletion for student: %', enrolled_student.usuario_estudiante;
    END LOOP;
    
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

-- Trigger que actualiza el progreso cuando se elimina una tarea
DROP TRIGGER IF EXISTS trigger_update_progress_on_task_delete ON tasks;

CREATE TRIGGER trigger_update_progress_on_task_delete
AFTER DELETE ON tasks
FOR EACH ROW
EXECUTE FUNCTION update_all_students_task_count_on_task_delete();

COMMENT ON FUNCTION update_all_students_task_count_on_task_delete() IS
'Recalcula progreso de todos los estudiantes cuando se elimina una tarea (actualiza totales, completadas, porcentaje y promedio)';

COMMENT ON TRIGGER trigger_update_progress_on_task_delete ON tasks IS
'Trigger que recalcula el progreso de todos los estudiantes cuando se elimina una tarea del curso';
