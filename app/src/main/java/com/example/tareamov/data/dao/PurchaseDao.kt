package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tareamov.data.entity.Purchase

// Make sure these methods exist in your PurchaseDao
@Dao
interface PurchaseDao {
    @Insert
    suspend fun insert(purchase: Purchase)

    @Query("SELECT COUNT(*) > 0 FROM purchases WHERE username = :username AND courseId = :courseId")
    suspend fun hasUserPurchasedCourse(username: String, courseId: Long): Boolean

    @Query("SELECT * FROM purchases")
    suspend fun getAllPurchases(): List<Purchase>

    // Remove this invalid method:
    // @Query("INSERT INTO purchases (username, courseId, purchaseDate, price) VALUES (:username, :courseId, :purchaseDate, :price)")
    // suspend fun insertPurchase(username: String, courseId: Long, purchaseDate: Long = System.currentTimeMillis(), price: Double = 0.0)
}