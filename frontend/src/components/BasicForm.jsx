import { useState } from 'react';
import './BasicForm.css';
import { useEffect } from "react";
import mixpanel from "../mixpanel";
import amplitude from "../amplitude";



const BasicForm = () => {
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    message: ''
  });


useEffect(() => {
  mixpanel.track("Page Loaded", {
    page: "Form Page"
  });
   amplitude.track("Page Loaded", {
    page: "Form Page"
  });

}, []);



 



  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: value
    }));
  };

 const handleSubmit = async (e) => {
  e.preventDefault();

  mixpanel.track("Submit Button Clicked", {
  page: "Form Page"


});
amplitude.track("Submit Button Clicked", {
  page: "Form Page"
});


  try {
    const response = await fetch("http://localhost:5000/api/forms/submit", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(formData),
    });

    if (!response.ok) {
      throw new Error("Failed to submit form");
    }

    const data = await response.json();
    console.log("Saved:", data);

    alert("Form submitted successfully!");

    // Optional: clear form
    setFormData({
      name: "",
      email: "",
      message: "",
    });

  } catch (error) {
    console.error(error);
    alert("Error submitting form");
  }
};


  return (
    <div className="form-container">
      <form className="basic-form" onSubmit={handleSubmit}>
      <div className="form-group">
        <label htmlFor="name">Name</label>
        <input
          type="text"
          id="name"
          name="name"
          value={formData.name}
          onChange={handleChange}
          required
        />
      </div>

      <div className="form-group">
        <label htmlFor="email">Email</label>
        <input
          type="email"
          id="email"
          name="email"
          value={formData.email}
          onChange={handleChange}
          required
        />
      </div>

      <div className="form-group">
        <label htmlFor="message">Message</label>
        <textarea
          id="message"
          name="message"
          value={formData.message}
          onChange={handleChange}
          required
        />
      </div>

      <button className="submit-button" type="submit">Submit</button>
      </form>
    </div>
  );
};

export default BasicForm;
