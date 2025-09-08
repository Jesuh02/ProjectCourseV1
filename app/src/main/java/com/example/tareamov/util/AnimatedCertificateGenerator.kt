package com.example.tareamov.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.tareamov.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AnimatedCertificateGenerator {
    private const val TAG = "AnimatedCertificateGenerator"

    fun generateAnimatedCertificate(
        context: Context,
        studentUsername: String,
        creatorUsername: String,
        courseName: String,
        courseTopic: String,
        grade: String
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Toast.makeText(context, "Generando certificado animado...", Toast.LENGTH_SHORT).show()

                // Get student and creator names from database
                val db = AppDatabase.getDatabase(context)

                val studentName = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(studentUsername)
                    if (user != null) {
                        val persona = db.personaDao().getPersonaById(user.personaId)
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}"
                    } else {
                        studentUsername
                    }
                }

                val creatorName = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(creatorUsername)
                    if (user != null) {
                        val persona = db.personaDao().getPersonaById(user.personaId)
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}"
                    } else {
                        creatorUsername
                    }
                }

                // Generate HTML certificate with animations
                val htmlContent = generateAnimatedHtml(
                    studentName,
                    courseName,
                    courseTopic,
                    creatorName,
                    grade
                )

                // Save HTML file
                val fileName = "Certificado_Animado_${courseName.replace(" ", "_")}_${System.currentTimeMillis()}.html"
                val file = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    fileName
                )

                withContext(Dispatchers.IO) {
                    FileWriter(file).use { writer ->
                        writer.write(htmlContent)
                    }
                }

                // Open HTML file in browser
                openHtmlFile(context, file)

                Toast.makeText(context, "Certificado animado generado con éxito", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error generating animated certificate", e)
                Toast.makeText(
                    context,
                    "Error al generar certificado animado: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }    private fun generateAnimatedHtml(
        studentName: String,
        courseName: String,
        courseTopic: String,
        creatorName: String,
        grade: String
    ): String {
        val dateFormat = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("es", "ES"))
        val currentDate = dateFormat.format(Date())

        val displayTopic = courseTopic.ifEmpty { courseName }

        return """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Certificado Cyberpunk</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Orbitron:wght@300;400;700;900&family=Rajdhani:wght@300;400;500;600;700&display=swap');
        
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        :root {
            --neon-cyan: #00ffff;
            --neon-magenta: #ff0080;
            --neon-yellow: #ffff00;
            --neon-orange: #ff8800;
            --dark-bg: #0a0a0f;
            --darker-bg: #050508;
        }

        body {
            font-family: 'Rajdhani', 'Orbitron', monospace;
            background: linear-gradient(135deg, #0a0a0f 0%, #1a0a2e 25%, #16213e 50%, #0f3460 75%, #0a0a0f 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
            padding: 20px;
            position: relative;
            overflow: hidden;
        }
        
        /* Partículas de fondo cyberpunk */
        .cyber-bg {
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: 
                radial-gradient(circle at 20% 80%, rgba(0, 255, 255, 0.1) 0%, transparent 50%),
                radial-gradient(circle at 80% 20%, rgba(255, 0, 128, 0.1) 0%, transparent 50%),
                radial-gradient(circle at 40% 40%, rgba(255, 255, 0, 0.05) 0%, transparent 50%);
            animation: cyberPulse 8s ease-in-out infinite;
            z-index: 1;
        }
        
        @keyframes cyberPulse {
            0%, 100% { opacity: 0.8; transform: scale(1); }
            50% { opacity: 1; transform: scale(1.05); }
        }
        
        /* Contenedor principal del certificado */
        .certificate-container {
            width: 100%;
            max-width: 380px;
            aspect-ratio: 9/16;
            position: relative;
            z-index: 10;
            animation: certificateEntrance 2s ease-out;
        }
        
        @keyframes certificateEntrance {
            0% { 
                opacity: 0;
                transform: scale(0.8) rotateY(20deg);
                filter: blur(10px);
            }
            100% { 
                opacity: 1;
                transform: scale(1) rotateY(0deg);
                filter: blur(0px);
            }
        }
        
        /* Marco principal con efecto neón */
        .main-frame {
            position: absolute;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: linear-gradient(145deg, #0f1419 0%, #1a1f2e 30%, #0f1419 100%);
            border-radius: 20px;
            border: 3px solid transparent;
            background-clip: padding-box;
            box-shadow: 
                0 0 30px rgba(0, 255, 255, 0.5),
                0 0 60px rgba(0, 255, 255, 0.3),
                inset 0 0 30px rgba(0, 255, 255, 0.1);
            animation: frameGlow 4s ease-in-out infinite alternate;
        }
        
        .main-frame::before {
            content: '';
            position: absolute;
            top: -3px;
            left: -3px;
            right: -3px;
            bottom: -3px;
            background: linear-gradient(45deg, #00ffff, #ff0080, #00ffff, #ff8800, #00ffff);
            border-radius: 20px;
            z-index: -1;
            animation: borderRotate 6s linear infinite;
        }
        
        @keyframes frameGlow {
            0% { 
                box-shadow: 
                    0 0 30px rgba(0, 255, 255, 0.4),
                    0 0 60px rgba(0, 255, 255, 0.2),
                    inset 0 0 30px rgba(0, 255, 255, 0.1);
            }
            100% { 
                box-shadow: 
                    0 0 50px rgba(0, 255, 255, 0.8),
                    0 0 100px rgba(0, 255, 255, 0.4),
                    inset 0 0 50px rgba(0, 255, 255, 0.2);
            }
        }
        
        @keyframes borderRotate {
            0% { background: linear-gradient(45deg, #00ffff, #ff0080, #00ffff, #ff8800, #00ffff); }
            25% { background: linear-gradient(135deg, #ff0080, #00ffff, #ff8800, #00ffff, #ff0080); }
            50% { background: linear-gradient(225deg, #00ffff, #ff8800, #00ffff, #ff0080, #00ffff); }
            75% { background: linear-gradient(315deg, #ff8800, #00ffff, #ff0080, #00ffff, #ff8800); }
            100% { background: linear-gradient(45deg, #00ffff, #ff0080, #00ffff, #ff8800, #00ffff); }
        }
        
        /* Badge de calificación */
        .grade-badge {
            position: absolute;
            top: 20px;
            left: 20px;
            background: linear-gradient(135deg, #ffff00, #ff8800);
            color: #000;
            padding: 10px 16px;
            border-radius: 20px;
            font-family: 'Orbitron', monospace;
            font-size: 13px;
            font-weight: 700;
            letter-spacing: 0.5px;
            box-shadow: 
                0 0 20px rgba(255, 255, 0, 0.8),
                0 4px 15px rgba(0, 0, 0, 0.3);
            animation: badgeEntrance 1.5s ease-out 0.5s both, badgePulse 3s ease-in-out infinite 2s;
            z-index: 15;
            text-transform: uppercase;
        }
        
        @keyframes badgeEntrance {
            0% { 
                transform: translateX(-100px) rotate(-10deg);
                opacity: 0;
            }
            100% { 
                transform: translateX(0) rotate(0deg);
                opacity: 1;
            }
        }
        
        @keyframes badgePulse {
            0%, 100% { 
                transform: scale(1);
                box-shadow: 0 0 20px rgba(255, 255, 0, 0.8);
            }
            50% { 
                transform: scale(1.05);
                box-shadow: 0 0 30px rgba(255, 255, 0, 1);
            }
        }
        
        /* Contenido principal */
        .certificate-content {
            position: relative;
            z-index: 12;
            padding: 80px 30px 30px;
            height: 100%;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            text-align: center;
        }
        
        /* Sección superior */
        .header-section {
            flex: 0 0 auto;
        }
        
        .main-title {
            font-family: 'Orbitron', monospace;
            font-size: clamp(28px, 7vw, 36px);
            font-weight: 900;
            color: #00ffff;
            text-shadow: 
                0 0 10px #00ffff,
                0 0 20px #00ffff,
                0 0 30px #00ffff,
                0 0 40px #00ffff;
            letter-spacing: 6px;
            margin-bottom: 8px;
            animation: titleEntrance 2s ease-out 0.8s both, titleGlow 4s ease-in-out infinite 3s;
            text-transform: uppercase;
        }
        
        @keyframes titleEntrance {
            0% { 
                transform: translateY(-30px) scale(0.9);
                opacity: 0;
                filter: blur(5px);
            }
            100% { 
                transform: translateY(0) scale(1);
                opacity: 1;
                filter: blur(0px);
            }
        }
        
        @keyframes titleGlow {
            0%, 100% { 
                text-shadow: 
                    0 0 10px #00ffff,
                    0 0 20px #00ffff,
                    0 0 30px #00ffff;
            }
            50% { 
                text-shadow: 
                    0 0 15px #00ffff,
                    0 0 25px #00ffff,
                    0 0 35px #00ffff,
                    0 0 45px #00ffff;
            }
        }
        
        .subtitle {
            font-family: 'Orbitron', monospace;
            font-size: clamp(14px, 3.5vw, 18px);
            font-weight: 600;
            color: #00ffff;
            text-shadow: 0 0 15px #00ffff;
            letter-spacing: 3px;
            margin-bottom: 40px;
            animation: subtitleEntrance 2s ease-out 1s both;
            text-transform: uppercase;
        }
        
        @keyframes subtitleEntrance {
            0% { 
                transform: translateX(-50px);
                opacity: 0;
            }
            100% { 
                transform: translateX(0);
                opacity: 1;
            }
        }
        
        /* Sección central */
        .content-section {
            flex: 1;
            display: flex;
            flex-direction: column;
            justify-content: center;
            padding: 20px 0;
        }
        
        .award-text {
            font-family: 'Rajdhani', sans-serif;
            font-size: 16px;
            color: #ffff00;
            font-weight: 500;
            text-shadow: 0 0 10px #ffff00;
            margin-bottom: 15px;
            animation: textEntrance 2s ease-out 1.2s both;
        }
        
        @keyframes textEntrance {
            0% { 
                transform: translateY(20px);
                opacity: 0;
            }
            100% { 
                transform: translateY(0);
                opacity: 1;
            }
        }
        
        .student-name {
            font-family: 'Orbitron', monospace;
            font-size: clamp(22px, 5.5vw, 28px);
            font-weight: 900;
            color: #00ffff;
            text-shadow: 
                0 0 15px #00ffff,
                0 0 25px #00ffff,
                0 0 35px #00ffff;
            letter-spacing: 4px;
            margin: 15px 0 20px;
            animation: nameEntrance 2s ease-out 1.4s both, nameGlow 5s ease-in-out infinite 4s;
            line-height: 1.1;
            text-transform: uppercase;
        }
        
        @keyframes nameEntrance {
            0% { 
                transform: scale(0.8) rotateX(20deg);
                opacity: 0;
                filter: blur(3px);
            }
            100% { 
                transform: scale(1) rotateX(0deg);
                opacity: 1;
                filter: blur(0px);
            }
        }
        
        @keyframes nameGlow {
            0%, 100% { 
                text-shadow: 
                    0 0 15px #00ffff,
                    0 0 25px #00ffff,
                    0 0 35px #00ffff;
                transform: scale(1);
            }
            50% { 
                text-shadow: 
                    0 0 20px #00ffff,
                    0 0 30px #00ffff,
                    0 0 40px #00ffff,
                    0 0 50px #00ffff;
                transform: scale(1.02);
            }
        }
        
        .completion-text {
            font-family: 'Rajdhani', sans-serif;
            font-size: 14px;
            color: #ffff00;
            font-weight: 500;
            text-shadow: 0 0 10px #ffff00;
            margin-bottom: 15px;
            animation: textEntrance 2s ease-out 1.6s both;
        }
        
        .course-name {
            font-family: 'Orbitron', monospace;
            font-size: clamp(18px, 4.5vw, 24px);
            font-weight: 700;
            color: #ffff00;
            text-shadow: 
                0 0 15px #ffff00,
                0 0 25px #ffff00,
                0 0 35px #ffff00;
            letter-spacing: 3px;
            margin-top: 10px;
            animation: courseEntrance 2s ease-out 1.8s both, courseGlow 4s ease-in-out infinite 5s;
            line-height: 1.2;
            text-transform: uppercase;
        }
        
        @keyframes courseEntrance {
            0% { 
                transform: translateY(30px) scale(0.9);
                opacity: 0;
            }
            100% { 
                transform: translateY(0) scale(1);
                opacity: 1;
            }
        }
        
        @keyframes courseGlow {
            0%, 100% { 
                text-shadow: 
                    0 0 15px #ffff00,
                    0 0 25px #ffff00,
                    0 0 35px #ffff00;
            }
            50% { 
                text-shadow: 
                    0 0 20px #ffff00,
                    0 0 30px #ffff00,
                    0 0 40px #ffff00,
                    0 0 50px #ffff00;
            }
        }
        
        /* Sparkles decorativos */
        .sparkles {
            position: absolute;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            pointer-events: none;
            z-index: 8;
        }
        
        .sparkle {
            position: absolute;
            color: #ffff00;
            font-size: 14px;
            font-weight: bold;
            text-shadow: 0 0 10px #ffff00;
            animation: sparkleFloat 6s ease-in-out infinite;
        }
        
        .sparkle:nth-child(1) { top: 22%; left: 8%; animation-delay: 0s; }
        .sparkle:nth-child(2) { top: 22%; right: 8%; animation-delay: 1.5s; }
        .sparkle:nth-child(3) { top: 42%; left: 5%; animation-delay: 3s; }
        .sparkle:nth-child(4) { top: 42%; right: 5%; animation-delay: 0.8s; }
        .sparkle:nth-child(5) { top: 62%; left: 12%; animation-delay: 2.2s; }
        .sparkle:nth-child(6) { top: 62%; right: 12%; animation-delay: 4s; }
        
        @keyframes sparkleFloat {
            0%, 100% { 
                transform: translateY(0) scale(1) rotate(0deg);
                opacity: 0.7;
            }
            25% { 
                transform: translateY(-15px) scale(1.3) rotate(90deg);
                opacity: 1;
            }
            50% { 
                transform: translateY(-10px) scale(1.1) rotate(180deg);
                opacity: 0.8;
            }
            75% { 
                transform: translateY(-20px) scale(1.2) rotate(270deg);
                opacity: 1;
            }
        }
        
        /* Sección inferior */
        .footer-section {
            flex: 0 0 auto;
            padding-top: 20px;
        }
        
        .signature-section {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 20px;
            animation: signatureEntrance 2s ease-out 2s both;
        }
        
        @keyframes signatureEntrance {
            0% { 
                transform: translateY(30px);
                opacity: 0;
            }
            100% { 
                transform: translateY(0);
                opacity: 1;
            }
        }
        
        .signature-item {
            flex: 1;
            text-align: center;
            margin: 0 10px;
        }
        
        .signature-line {
            width: 100%;
            height: 2px;
            background: linear-gradient(90deg, transparent, #00ffff, transparent);
            margin-bottom: 8px;
            border-radius: 1px;
            animation: lineGlow 3s ease-in-out infinite alternate;
        }
        
        @keyframes lineGlow {
            0% { 
                opacity: 0.6;
                box-shadow: 0 0 5px #00ffff;
            }
            100% { 
                opacity: 1;
                box-shadow: 0 0 15px #00ffff;
            }
        }
        
        .signature-text {
            font-family: 'Rajdhani', sans-serif;
            font-size: 11px;
            color: #ffff00;
            font-weight: 600;
            text-shadow: 0 0 8px #ffff00;
            margin-bottom: 3px;
        }
        
        .signature-label {
            font-family: 'Orbitron', monospace;
            font-size: 9px;
            color: #ff0080;
            font-weight: 700;
            text-shadow: 0 0 6px #ff0080;
            letter-spacing: 1px;
            text-transform: uppercase;
        }
        
        /* Barras de acento inferiores */
        .bottom-accents {
            display: flex;
            justify-content: space-between;
            animation: accentsEntrance 2s ease-out 2.2s both;
        }
        
        @keyframes accentsEntrance {
            0% { 
                transform: scaleX(0);
                opacity: 0;
            }
            100% { 
                transform: scaleX(1);
                opacity: 1;
            }
        }
        
        .accent-bar {
            width: 50px;
            height: 3px;
            background: linear-gradient(90deg, #ffff00, #ff8800);
            border-radius: 2px;
            animation: barGlow 3s ease-in-out infinite alternate;
        }
        
        @keyframes barGlow {
            0% { 
                box-shadow: 0 0 5px #ffff00;
                transform: scaleY(1);
            }
            100% { 
                box-shadow: 0 0 15px #ffff00;
                transform: scaleY(1.3);
            }
        }
        
        /* Efectos interactivos */
        .certificate-container:hover {
            transform: scale(1.02);
            transition: transform 0.3s ease;
        }
        
        .certificate-container:hover .main-frame {
            animation-duration: 2s;
        }
        
        /* Responsive design */
        @media (max-width: 480px) {
            .certificate-container {
                max-width: 340px;
            }
            
            .certificate-content {
                padding: 70px 25px 25px;
            }
            
            .grade-badge {
                top: 15px;
                left: 15px;
                padding: 8px 12px;
                font-size: 12px;
            }
            
            .sparkle {
                font-size: 12px;
            }
        }
        
        /* Efectos de partículas adicionales */
        .cyber-particles {
            position: absolute;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            pointer-events: none;
            z-index: 2;
        }
        
        .cyber-particle {
            position: absolute;
            width: 2px;
            height: 2px;
            background: #00ffff;
            border-radius: 50%;
            animation: particleDrift 10s linear infinite;
        }
        
        @keyframes particleDrift {
            0% { 
                transform: translateY(100vh) translateX(0);
                opacity: 0;
            }
            10% { opacity: 1; }
            90% { opacity: 1; }
            100% { 
                transform: translateY(-10vh) translateX(50px);
                opacity: 0;
            }
        }
    </style>
</head>
<body>
    <!-- Fondo cyberpunk -->
    <div class="cyber-bg"></div>
    
    <!-- Partículas flotantes -->
    <div class="cyber-particles">
        <div class="cyber-particle" style="left: 10%; animation-delay: 0s;"></div>
        <div class="cyber-particle" style="left: 20%; animation-delay: 2s;"></div>
        <div class="cyber-particle" style="left: 30%; animation-delay: 4s;"></div>
        <div class="cyber-particle" style="left: 40%; animation-delay: 6s;"></div>
        <div class="cyber-particle" style="left: 50%; animation-delay: 8s;"></div>
        <div class="cyber-particle" style="left: 60%; animation-delay: 1s;"></div>
        <div class="cyber-particle" style="left: 70%; animation-delay: 3s;"></div>
        <div class="cyber-particle" style="left: 80%; animation-delay: 5s;"></div>
        <div class="cyber-particle" style="left: 90%; animation-delay: 7s;"></div>
    </div>
    
    <!-- Contenedor principal del certificado -->
    <div class="certificate-container">
        <!-- Marco principal -->
        <div class="main-frame"></div>        
        <!-- Badge de calificación -->
        <div class="grade-badge">Calificación: ${grade}/10</div>
        
        <!-- Contenido del certificado -->
        <div class="certificate-content">
            <!-- Sección superior -->
            <div class="header-section">
                <div class="main-title">CERTIFICADO</div>
                <div class="subtitle">DE FINALIZACIÓN</div>
            </div>
            
            <!-- Sección central -->
            <div class="content-section">                <div class="award-text">Se otorga a</div>
                <div class="student-name">${studentName.uppercase()}</div>
                <div class="completion-text">por haber completado con éxito</div>
                <div class="course-name">${courseName.uppercase()}</div> 
            </div>
            
            <!-- Sección inferior -->
            <div class="footer-section">
                <div class="signature-section">
                    <div class="signature-item">
                        <div class="signature-line"></div>
                        <div class="signature-text">${currentDate}</div>
                        <div class="signature-label">FECHA</div>
                    </div>
                    <div class="signature-item">
                        <div class="signature-line"></div>
                        <div class="signature-text">${creatorName}</div>
                        <div class="signature-label">CREADOR</div>
                    </div>
                </div>
                
                <div class="bottom-accents">
                    <div class="accent-bar"></div>
                    <div class="accent-bar"></div>
                </div>
            </div>
        </div>
        
        <!-- Sparkles decorativos -->
        <div class="sparkles">
            <div class="sparkle">+</div>
            <div class="sparkle">+</div>
            <div class="sparkle">+</div>
            <div class="sparkle">+</div>
            <div class="sparkle">+</div>
            <div class="sparkle">+</div>
        </div>
    </div>

    <script>
        // Efectos interactivos cyberpunk
        document.addEventListener('DOMContentLoaded', function() {
            const container = document.querySelector('.certificate-container');
            
            // Efecto de glitch aleatorio
            function createGlitchEffect() {
                const elements = document.querySelectorAll('.main-title, .student-name, .course-name');
                elements.forEach(el => {
                    if (Math.random() > 0.95) {
                        el.style.textShadow = '2px 0 #ff0080, -2px 0 #00ffff';
                        setTimeout(() => {
                            el.style.textShadow = '';
                        }, 100);
                    }
                });
            }
            
            // Sparkles interactivos
            function createInteractiveSparkle(x, y) {
                const sparkle = document.createElement('div');
                sparkle.innerHTML = ['✦', '✧', '⟡', '◊'][Math.floor(Math.random() * 4)];
                sparkle.style.position = 'absolute';
                sparkle.style.left = x + 'px';
                sparkle.style.top = y + 'px';
                sparkle.style.color = ['#00ffff', '#ff0080', '#ffff00'][Math.floor(Math.random() * 3)];
                sparkle.style.fontSize = '12px';
                sparkle.style.pointerEvents = 'none';
                sparkle.style.zIndex = '20';
                sparkle.style.textShadow = '0 0 10px currentColor';
                sparkle.style.animation = 'sparkleFloat 2s ease-out forwards';
                
                document.body.appendChild(sparkle);
                
                setTimeout(() => {
                    if (document.body.contains(sparkle)) {
                        document.body.removeChild(sparkle);
                    }
                }, 2000);
            }
            
            // Efectos de mouse
            container.addEventListener('mousemove', function(e) {
                if (Math.random() > 0.85) {
                    createInteractiveSparkle(e.clientX, e.clientY);
                }
            });
            
            // Efecto de celebración al hacer clic
            container.addEventListener('click', function(e) {
                const rect = container.getBoundingClientRect();
                const centerX = rect.left + rect.width / 2;
                const centerY = rect.top + rect.height / 2;
                
                for (let i = 0; i < 15; i++) {
                    setTimeout(() => {
                        const angle = (i / 15) * Math.PI * 2;
                        const distance = 40 + Math.random() * 40;
                        const x = centerX + Math.cos(angle) * distance;
                        const y = centerY + Math.sin(angle) * distance;
                        createInteractiveSparkle(x, y);
                    }, i * 30);
                }
            });
            
            // Generar partículas adicionales
            function generateCyberParticle() {
                const particle = document.createElement('div');
                particle.className = 'cyber-particle';
                particle.style.left = Math.random() * 100 + '%';
                particle.style.animationDelay = '0s';
                particle.style.animationDuration = (8 + Math.random() * 4) + 's';
                
                document.querySelector('.cyber-particles').appendChild(particle);
                
                setTimeout(() => {
                    if (particle.parentNode) {
                        particle.parentNode.removeChild(particle);
                    }
                }, 12000);
            }
            
            // Efectos periódicos
            setInterval(createGlitchEffect, 3000);
            setInterval(generateCyberParticle, 1500);
            
            // Funcionalidad de impresión
            document.addEventListener('keydown', function(e) {
                if ((e.ctrlKey || e.metaKey) && e.key === 'p') {
                    e.preventDefault();
                    window.print();
                }
            });
        });
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun openHtmlFile(context: Context, file: File) {
        try {            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.service.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Try to open with browser or HTML viewer
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Share the file if no app can open it
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/html"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Abrir certificado animado"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening HTML file", e)
            Toast.makeText(
                context,
                "Error al abrir el certificado animado: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
